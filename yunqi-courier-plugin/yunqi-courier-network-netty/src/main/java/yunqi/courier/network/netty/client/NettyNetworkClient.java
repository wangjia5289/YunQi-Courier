package yunqi.courier.network.netty.client;

import yunqi.courier.common.constant.CourierConstants;
import yunqi.courier.common.config.CourierConfig;
import yunqi.courier.common.exception.RemotingException;
import yunqi.courier.common.protocol.CourierRequest;
import yunqi.courier.common.protocol.CourierResponse;
import yunqi.courier.common.protocol.ProtocolMessage;
import yunqi.courier.common.protocol.ProtocolMessageType;
import yunqi.courier.common.service.ServiceEndpoint;
import yunqi.courier.kernel.spi.core.SPI;
import yunqi.courier.kernel.spi.core.ServiceProviderLoader;
import yunqi.courier.kernel.spi.network.NetworkClient;
import yunqi.courier.kernel.spi.serialization.Serializer;
import yunqi.courier.network.netty.handler.ClientHandler;
import yunqi.courier.network.netty.handler.YunqiProtocolDecoder;
import yunqi.courier.network.netty.handler.YunqiProtocolEncoder;
import yunqi.courier.network.netty.handler.HeartbeatHandler;
import yunqi.courier.network.netty.codec.PayloadTransformer;
import yunqi.courier.network.netty.ssl.TlsContextFactory;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.concurrent.DefaultThreadFactory;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@SPI("netty")
public class NettyNetworkClient implements NetworkClient {

    private static final System.Logger LOGGER = System.getLogger(NettyNetworkClient.class.getName());

    private final EventLoopGroup eventLoopGroup;

    private final Bootstrap bootstrap;

    private final PendingRequestManager pendingRequestManager = new PendingRequestManager();

    private final Map<String, Channel> channelMap = new ConcurrentHashMap<>();

    private final AtomicBoolean closed = new AtomicBoolean();

    private final ScheduledExecutorService reconnectExecutor;

    private final Map<String, ReconnectState> reconnectStates = new ConcurrentHashMap<>();

    private volatile Serializer serializer;

    private volatile int maxFrameLength = CourierConstants.DEFAULT_MAX_FRAME_LENGTH;

    private volatile int connectTimeoutMillis = CourierConstants.DEFAULT_CONNECT_TIMEOUT_MILLIS;

    private volatile int maxPendingRequests = CourierConstants.DEFAULT_MAX_PENDING_REQUESTS;

    private volatile boolean autoReconnect = true;

    private volatile int reconnectBaseDelayMillis = 100;

    private volatile int reconnectMaxDelayMillis = 5_000;

    private volatile CourierConfig tlsConfig;

    private volatile SslContext tlsContext;

    private volatile PayloadTransformer payloadTransformer = PayloadTransformer.fromConfig(new CourierConfig());

    public NettyNetworkClient() {
        this.eventLoopGroup = new NioEventLoopGroup(
                Math.min(32, Math.max(2, Runtime.getRuntime().availableProcessors() * 2)),
                new DefaultThreadFactory("yunqi-courier-client-worker", true)
        );
        this.reconnectExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "yunqi-courier-client-reconnect");
            thread.setDaemon(true);
            return thread;
        });
        this.bootstrap = new Bootstrap();
        this.bootstrap.group(eventLoopGroup)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMillis)
                .handler(channelInitializer(null, -1));
    }

    @Override
    public void configure(CourierConfig config) {
        try {
            this.serializer = ServiceProviderLoader.newInstance(Serializer.class, config.getSerialization());
            this.maxFrameLength = config.getMaxFrameLength();
            this.connectTimeoutMillis = config.getConnectTimeoutMillis();
            this.maxPendingRequests = config.getMaxPendingRequests();
            this.autoReconnect = config.isClientAutoReconnect();
            this.reconnectBaseDelayMillis = config.getClientReconnectBaseDelayMillis();
            this.reconnectMaxDelayMillis = config.getClientReconnectMaxDelayMillis();
            this.tlsConfig = config.copy();
            this.tlsContext = null;
            this.payloadTransformer = PayloadTransformer.fromConfig(config);
            this.bootstrap.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMillis);
        } catch (RuntimeException failure) {
            close();
            throw failure;
        }
    }

    @Override
    public CompletableFuture<CourierResponse> sendRequest(CourierRequest request, ServiceEndpoint endpoint, int timeoutMillis) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(endpoint, "endpoint");
        if (!endpoint.isValid()) {
            CompletableFuture<CourierResponse> invalid = new CompletableFuture<>();
            invalid.completeExceptionally(new RemotingException("Invalid service endpoint"));
            return invalid;
        }
        if (timeoutMillis <= 0) {
            throw new IllegalArgumentException("timeoutMillis must be positive");
        }
        CompletableFuture<CourierResponse> responseFuture = new CompletableFuture<>();
        if (closed.get()) {
            responseFuture.completeExceptionally(new RemotingException("Network client is closed"));
            return responseFuture;
        }
        final long requestId;
        try {
            if (request.getRequestId() == null || request.getRequestId().isBlank()) {
                throw new IllegalArgumentException("request.requestId must not be blank");
            }
            requestId = Long.parseLong(request.getRequestId());
            if (requestId <= 0) {
                throw new IllegalArgumentException("request.requestId must be positive");
            }
        } catch (RuntimeException e) {
            responseFuture.completeExceptionally(new RemotingException("Invalid request id", e));
            return responseFuture;
        }
        final long currentRequestId = requestId;
        long startNanos = System.nanoTime();
        if (!pendingRequestManager.reserve(currentRequestId, responseFuture, maxPendingRequests)) {
            responseFuture.completeExceptionally(new RemotingException(
                    "Too many pending requests, limit=" + maxPendingRequests));
            return responseFuture;
        }
        responseFuture.whenComplete((ignored, failure) -> pendingRequestManager.remove(currentRequestId));
        try {
            if (closed.get()) {
                throw new RemotingException("Network client is closed");
            }
            Channel channel = getChannel(endpoint, timeoutMillis);
            if (!pendingRequestManager.associateChannel(currentRequestId, channel)) {
                throw new RemotingException("Network client closed while connecting");
            }
            ProtocolMessage protocolMessage = new ProtocolMessage();
            protocolMessage.setVersion(CourierConstants.PROTOCOL_VERSION);
            protocolMessage.setMessageType(ProtocolMessageType.REQUEST.getCode());
            Serializer requestSerializer = serializer();
            protocolMessage.setSerializationType(requestSerializer.code());
            protocolMessage.setRequestId(currentRequestId);
            protocolMessage.setPayload(payloadTransformer.encode(requestSerializer.serialize(request)));
            channel.writeAndFlush(protocolMessage).addListener(future -> {
                if (!future.isSuccess()) {
                    pendingRequestManager.remove(currentRequestId);
                    responseFuture.completeExceptionally(new RemotingException(
                            "Write request failed, endpoint=" + endpoint.address(), future.cause()));
                }
            });
            long elapsedNanos = System.nanoTime() - startNanos;
            long remainingNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis) - elapsedNanos;
            if (remainingNanos <= 0) {
                pendingRequestManager.remove(currentRequestId);
                responseFuture.completeExceptionally(new RemotingException("Request deadline exceeded, requestId=" + request.getRequestId()));
                return responseFuture;
            }
            var timeoutTask = eventLoopGroup.schedule(() -> {
                CompletableFuture<CourierResponse> removed = pendingRequestManager.remove(currentRequestId);
                if (removed != null) {
                    removed.completeExceptionally(new RemotingException("Request timeout, requestId=" + request.getRequestId()));
                }
            }, remainingNanos, TimeUnit.NANOSECONDS);
            pendingRequestManager.setTimeoutTask(currentRequestId, timeoutTask);
        } catch (Exception e) {
            pendingRequestManager.remove(requestId);
            responseFuture.completeExceptionally(new RemotingException("Send request failed, endpoint=" + endpoint.address(), e));
        }
        return responseFuture;
    }

    @Override
    public CompletableFuture<Boolean> checkEndpoint(ServiceEndpoint endpoint, int timeoutMillis) {
        if (endpoint == null || !endpoint.isValid()) {
            return CompletableFuture.completedFuture(Boolean.FALSE);
        }
        if (timeoutMillis <= 0) {
            return CompletableFuture.completedFuture(Boolean.FALSE);
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        try {
            Channel channel = getChannel(endpoint, timeoutMillis);
            SslHandler sslHandler = channel.pipeline().get(SslHandler.class);
            if (sslHandler != null) {
                long remainingNanos = deadline - System.nanoTime();
                long remainingMillis = remainingNanos <= 0 ? 0
                        : Math.max(1, TimeUnit.NANOSECONDS.toMillis(remainingNanos));
                if (!sslHandler.handshakeFuture().await(remainingMillis, TimeUnit.MILLISECONDS)
                        || !sslHandler.handshakeFuture().isSuccess()) {
                    return CompletableFuture.completedFuture(Boolean.FALSE);
                }
            }
            return CompletableFuture.completedFuture(channel.isActive());
        } catch (RuntimeException | InterruptedException failure) {
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return CompletableFuture.completedFuture(Boolean.FALSE);
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        pendingRequestManager.failAll(new RemotingException("Network client is closed"));
        channelMap.values().forEach(Channel::close);
        reconnectStates.clear();
        reconnectExecutor.shutdownNow();
        io.netty.util.concurrent.Future<?> shutdown = eventLoopGroup.shutdownGracefully(0, 15,
                TimeUnit.SECONDS);
        // Netty callbacks may invoke close() from an event-loop thread. Waiting
        // for that same loop to terminate would deadlock or throw a blocking
        // operation exception; external callers still get graceful completion.
        boolean onEventLoop = false;
        for (io.netty.util.concurrent.EventExecutor executor : eventLoopGroup) {
            if (executor.inEventLoop()) {
                onEventLoop = true;
                break;
            }
        }
        if (!onEventLoop) {
            shutdown.syncUninterruptibly();
        }
    }

    @Override
    public synchronized void reloadTlsContext() {
        tlsContext = null;
        channelMap.values().forEach(Channel::close);
    }

    private Channel getChannel(ServiceEndpoint endpoint, int timeoutMillis) {
        return channelMap.compute(endpoint.address(), (address, oldChannel) -> {
            if (oldChannel != null && oldChannel.isActive()) {
                return oldChannel;
            }
            try {
                if (closed.get()) {
                    throw new RemotingException("Network client is closed");
                }
                Bootstrap endpointBootstrap = bootstrap.clone();
                endpointBootstrap.handler(channelInitializer(endpoint.getHost(), endpoint.getPort()));
                if (tlsEnabled()) {
                    ensureTlsContext();
                }
                var connectFuture = endpointBootstrap.connect(endpoint.getHost(), endpoint.getPort());
                if (!connectFuture.await(Math.min(timeoutMillis, connectTimeoutMillis), TimeUnit.MILLISECONDS)) {
                    connectFuture.cancel(true);
                    throw new RemotingException("Connect timeout, endpoint=" + endpoint.address());
                }
                if (!connectFuture.isSuccess()) {
                    throw new RemotingException("Connect failed, endpoint=" + endpoint.address(), connectFuture.cause());
                }
                Channel channel = connectFuture.channel();
                if (closed.get()) {
                    channel.close();
                    throw new RemotingException("Network client closed while connecting");
                }
                channel.closeFuture().addListener(ignored -> {
                    channelMap.remove(address, channel);
                    if (autoReconnect && !closed.get()) {
                        scheduleReconnect(endpoint);
                    }
                });
                return channel;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RemotingException("Connect interrupted, endpoint=" + endpoint.address(), e);
            }
        });
    }

    private Serializer serializer() {
        Serializer current = serializer;
        if (current == null) {
            current = ServiceProviderLoader.load(Serializer.class, CourierConstants.DEFAULT_SERIALIZATION);
            serializer = current;
        }
        return current;
    }

    private ChannelInitializer<SocketChannel> channelInitializer(String host, int port) {
        return new ChannelInitializer<>() {
            @Override
            protected void initChannel(SocketChannel ch) {
                if (tlsEnabled()) {
                    SslHandler sslHandler = ensureTlsContext().newHandler(ch.alloc(), host, port);
                    sslHandler.setHandshakeTimeoutMillis(connectTimeoutMillis);
                    ch.pipeline().addLast("tls", sslHandler);
                    sslHandler.handshakeFuture().addListener(future -> {
                        if (!future.isSuccess()) {
                            pendingRequestManager.fail(ch,
                                    new RemotingException("TLS handshake failed, endpoint="
                                            + host + ":" + port, future.cause()));
                            ch.close();
                        }
                    });
                }
                ch.pipeline()
                        .addLast(new IdleStateHandler(30, 15, 0, TimeUnit.SECONDS))
                        .addLast(new YunqiProtocolDecoder(maxFrameLength))
                        .addLast(new YunqiProtocolEncoder(maxFrameLength))
                        .addLast(new HeartbeatHandler())
                        .addLast(new ClientHandler(pendingRequestManager, serializer(), payloadTransformer));
            }
        };
    }

    private boolean tlsEnabled() {
        CourierConfig config = tlsConfig;
        return config != null && config.isTlsEnabled();
    }

    private synchronized SslContext ensureTlsContext() {
        if (!tlsEnabled()) {
            throw new IllegalStateException("TLS is not enabled");
        }
        if (tlsContext == null) {
            tlsContext = TlsContextFactory.createClientContext(tlsConfig);
        }
        return tlsContext;
    }

    private void scheduleReconnect(ServiceEndpoint endpoint) {
        if (!autoReconnect || closed.get() || endpoint == null || !endpoint.isValid()) {
            return;
        }
        String address = endpoint.address();
        ReconnectState state = reconnectStates.computeIfAbsent(address, ignored -> new ReconnectState());
        if (!state.scheduled.compareAndSet(false, true)) {
            return;
        }
        long delay = state.nextDelay(reconnectBaseDelayMillis, reconnectMaxDelayMillis);
        reconnectExecutor.schedule(() -> {
            state.scheduled.set(false);
            if (closed.get()) {
                reconnectStates.remove(address, state);
                return;
            }
            Channel current = channelMap.get(address);
            if (current != null && current.isActive()) {
                reconnectStates.remove(address, state);
                return;
            }
            try {
                getChannel(endpoint, connectTimeoutMillis);
                reconnectStates.remove(address, state);
            } catch (RuntimeException failure) {
                state.failures.incrementAndGet();
                LOGGER.log(System.Logger.Level.WARNING,
                        "RPC endpoint reconnect failed, endpoint=" + endpoint.address(), failure);
                scheduleReconnect(endpoint);
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    private static final class ReconnectState {
        private final AtomicBoolean scheduled = new AtomicBoolean();
        private final AtomicInteger failures = new AtomicInteger();

        private long nextDelay(int baseMillis, int maxMillis) {
            int attempts = Math.min(failures.get(), 20);
            long multiplier = 1L << attempts;
            return Math.min(maxMillis, Math.max(1L, baseMillis * multiplier));
        }
    }
}
