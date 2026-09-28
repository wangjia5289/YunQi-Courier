package yunqi.courier.registry.file;

import yunqi.courier.common.config.CourierConfig;
import yunqi.courier.common.service.ServiceEndpoint;
import yunqi.courier.common.service.ServiceMetadata;
import yunqi.courier.kernel.spi.core.SPI;
import yunqi.courier.kernel.spi.registry.RegistryListener;
import yunqi.courier.kernel.spi.registry.ServiceRegistry;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Small cross-process registry backed by an OS file lock. It is useful for
 * single-host deployments and smoke tests; a distributed registry is still
 * preferable for multi-host production.
 */
@SPI("file")
public final class FileServiceRegistry implements ServiceRegistry {

    private static final Object FILE_MUTEX = new Object();

    private final List<WatchRegistration> watches = new CopyOnWriteArrayList<>();
    private final List<ServiceMetadata> ownedServices = new CopyOnWriteArrayList<>();
    private ScheduledExecutorService scheduler;
    private Path path;
    private int ttlMillis = 30_000;
    private int reconnectMillis = 1_000;
    private volatile boolean closed = true;
    private boolean closing;

    @Override
    public synchronized void configure(CourierConfig config) {
        closing = true;
        closed = true;
        RuntimeException previousFailure;
        try {
            previousFailure = closeResources();
        } finally {
            closing = false;
        }
        if (previousFailure != null) {
            throw previousFailure;
        }
        path = Path.of(config.getRegistryFilePath() == null
                ? Path.of(System.getProperty("java.io.tmpdir"), "yunqi-courier-registry.db").toString()
                : config.getRegistryFilePath()).toAbsolutePath();
        ttlMillis = config.getRegistryLeaseTtlMillis();
        reconnectMillis = config.getRegistryWatchReconnectMillis();
        try {
            Files.createDirectories(path.getParent());
            if (!Files.exists(path)) {
                Files.createFile(path);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Create registry file failed, path=" + path, e);
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "yunqi-courier-file-registry-watch");
            thread.setDaemon(true);
            return thread;
        });
        closed = false;
    }

    @Override
    public synchronized void register(ServiceMetadata metadata) {
        requireConfigured();
        if (closed || scheduler == null) {
            throw new IllegalStateException("Registry is closed");
        }
        if (metadata == null) {
            throw new IllegalArgumentException("service metadata must not be null");
        }
        metadata.validate();
        mutate(records -> {
            records.removeIf(record -> record.metadata().equals(metadata));
            records.add(new Record(metadata.snapshot(), expiry()));
            return records;
        });
        ownedServices.removeIf(existing -> existing.equals(metadata));
        ownedServices.add(metadata.snapshot());
        notifyWatches(metadata.serviceKey());
    }

    @Override
    public synchronized void unregister(ServiceMetadata metadata) {
        if ((closed && !closing) || metadata == null || path == null) return;
        mutate(records -> {
            records.removeIf(record -> record.metadata().equals(metadata));
            return records;
        });
        ownedServices.remove(metadata);
        notifyWatches(metadata.serviceKey());
    }

    @Override
    public synchronized List<ServiceMetadata> discover(String serviceKey) {
        if (closed || path == null || serviceKey == null || serviceKey.isBlank()) return List.of();
        long now = System.currentTimeMillis();
        List<Record> records = readRecords();
        List<ServiceMetadata> result = new ArrayList<>();
        for (Record record : records) {
            if (record.expiresAt() > now && record.metadata().serviceKey().equals(serviceKey)) {
                result.add(record.metadata().snapshot());
            }
        }
        return List.copyOf(result);
    }

    @Override
    public synchronized void renew(ServiceMetadata metadata) {
        if (closed || metadata == null || path == null) return;
        mutate(records -> {
            for (int i = 0; i < records.size(); i++) {
                if (records.get(i).metadata().equals(metadata)) {
                    records.set(i, new Record(records.get(i).metadata(), expiry()));
                }
            }
            return records;
        });
    }

    @Override
    public synchronized void updateHealth(ServiceMetadata metadata, boolean healthy) {
        if (closed || metadata == null || path == null) return;
        mutate(records -> {
            for (int i = 0; i < records.size(); i++) {
                Record current = records.get(i);
                if (current.metadata().equals(metadata)) {
                    ServiceMetadata updated = current.metadata().snapshot();
                    Map<String, String> attributes = new HashMap<>(updated.getAttributes());
                    attributes.put(yunqi.courier.common.constant.CourierConstants.SERVICE_ATTRIBUTE_HEALTHY,
                            Boolean.toString(healthy));
                    updated.setAttributes(attributes);
                    records.set(i, new Record(updated, current.expiresAt()));
                }
            }
            return records;
        });
        notifyWatches(metadata.serviceKey());
    }

    @Override
    public synchronized AutoCloseable watch(String serviceKey, RegistryListener listener) {
        if (closed || serviceKey == null || listener == null) return () -> { };
        WatchRegistration registration = new WatchRegistration(serviceKey, listener);
        watches.add(registration);
        // Keep initial delivery consistent with polling notifications: a
        // broken consumer callback must not leak or invalidate the watch.
        notifyWatch(registration);
        if (scheduler != null && reconnectMillis > 0) {
            registration.task = scheduler.scheduleWithFixedDelay(
                    () -> notifyWatch(registration), reconnectMillis, reconnectMillis, TimeUnit.MILLISECONDS);
        }
        return () -> closeWatch(registration);
    }

    @Override
    public synchronized void close() {
        RuntimeException failure;
        closing = true;
        closed = true;
        try {
            failure = closeResources();
        } finally {
            closing = false;
        }
        if (failure != null) {
            throw failure;
        }
    }

    private RuntimeException closeResources() {
        RuntimeException failure = null;
        for (ServiceMetadata metadata : List.copyOf(ownedServices)) {
            try {
                unregister(metadata);
            } catch (RuntimeException e) {
                if (failure == null) {
                    failure = new RuntimeException("Failed to unregister file registry service(s)");
                }
                failure.addSuppressed(e);
            }
        }
        ownedServices.clear();
        for (WatchRegistration watch : List.copyOf(watches)) {
            try {
                closeWatch(watch);
            } catch (RuntimeException e) {
                if (failure == null) {
                    failure = new RuntimeException("Failed to close file registry watch(s)");
                }
                failure.addSuppressed(e);
            }
        }
        watches.clear();
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        return failure;
    }

    private long expiry() {
        return System.currentTimeMillis() + ttlMillis;
    }

    private void notifyWatches(String serviceKey) {
        watches.stream().filter(watch -> watch.serviceKey.equals(serviceKey)).forEach(this::notifyWatch);
    }

    private void notifyWatch(WatchRegistration watch) {
        try {
            watch.listener.onChange(watch.serviceKey, discover(watch.serviceKey));
        } catch (RuntimeException ignored) {
            // A consumer callback must not stop watch polling.
        }
    }

    private void closeWatch(WatchRegistration watch) {
        if (!watches.remove(watch)) return;
        if (watch.task != null) watch.task.cancel(false);
    }

    private void requireConfigured() {
        if (path == null) throw new IllegalStateException("Registry is not configured");
    }

    private synchronized void mutate(java.util.function.UnaryOperator<List<Record>> operation) {
        requireConfigured();
        synchronized (FILE_MUTEX) {
            try (FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE,
                    StandardOpenOption.READ, StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock()) {
                List<Record> records = readRecords(channel);
                long now = System.currentTimeMillis();
                records.removeIf(record -> record.expiresAt() <= now);
                records = operation.apply(records);
                byte[] data = encode(records).getBytes(StandardCharsets.UTF_8);
                channel.truncate(0);
                channel.position(0);
                channel.write(ByteBuffer.wrap(data));
                channel.force(true);
            } catch (IOException e) {
                throw new IllegalStateException("Update registry file failed, path=" + path, e);
            }
        }
    }

    private List<Record> readRecords() {
        synchronized (FILE_MUTEX) {
            try (FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ);
                 FileLock ignored = channel.lock(0L, Long.MAX_VALUE, true)) {
                return readRecords(channel);
            } catch (IOException e) {
                throw new IllegalStateException("Read registry file failed, path=" + path, e);
            }
        }
    }

    private List<Record> readRecords(FileChannel channel) throws IOException {
        channel.position(0);
        String text = StandardCharsets.UTF_8.decode(channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())).toString();
        List<Record> records = new ArrayList<>();
        for (String line : text.split("\\R")) {
            if (line.isBlank()) continue;
            try {
                String[] fields = line.split("\\t", -1);
                if (fields.length != 5) {
                    throw new IllegalArgumentException("Invalid registry record field count");
                }
                String[] key = decode(fields[0]).split("\\u0000", -1);
                if (key.length != 3) {
                    throw new IllegalArgumentException("Invalid registry service key");
                }
                ServiceMetadata metadata = new ServiceMetadata();
                metadata.setServiceName(key[0]);
                metadata.setGroup(key[1]);
                metadata.setVersion(key[2]);
                metadata.setEndpoint(new ServiceEndpoint(decode(fields[1]), Integer.parseInt(fields[2])));
                metadata.setAttributes(decodeAttributes(fields[4]));
                metadata.validate();
                records.add(new Record(metadata, Long.parseLong(fields[3])));
            } catch (RuntimeException ignored) {
                // Ignore a torn/corrupt line and keep other registrations available.
            }
        }
        return records;
    }

    private String encode(List<Record> records) {
        StringBuilder output = new StringBuilder();
        for (Record record : records) {
            ServiceMetadata metadata = record.metadata();
            output.append(encode(metadata.getServiceName() + "\u0000" + metadata.getGroup() + "\u0000" + metadata.getVersion()))
                    .append('\t').append(encode(metadata.getEndpoint().getHost()))
                    .append('\t').append(metadata.getEndpoint().getPort())
                    .append('\t').append(record.expiresAt())
                    .append('\t').append(encodeAttributes(metadata.getAttributes())).append('\n');
        }
        return output.toString();
    }

    private String encodeAttributes(Map<String, String> attributes) {
        StringBuilder result = new StringBuilder();
        attributes.forEach((key, value) -> {
            if (result.length() > 0) result.append('\u0001');
            result.append(encode(key)).append('=').append(encode(value));
        });
        return encode(result.toString());
    }

    private Map<String, String> decodeAttributes(String value) {
        Map<String, String> result = new HashMap<>();
        if (value.isBlank()) return result;
        for (String item : decode(value).split("\\u0001")) {
            int separator = item.indexOf('=');
            if (separator > 0) result.put(decode(item.substring(0, separator)), decode(item.substring(separator + 1)));
        }
        return result;
    }

    private String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private String decode(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private record Record(ServiceMetadata metadata, long expiresAt) {
    }

    private static final class WatchRegistration {
        private final String serviceKey;
        private final RegistryListener listener;
        private volatile java.util.concurrent.ScheduledFuture<?> task;

        private WatchRegistration(String serviceKey, RegistryListener listener) {
            this.serviceKey = serviceKey;
            this.listener = listener;
        }
    }
}
