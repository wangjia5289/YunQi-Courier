package yunqi.courier.network.netty.server;

import yunqi.courier.common.exception.RemotingException;
import yunqi.courier.common.constant.CourierConstants;
import yunqi.courier.common.config.CourierConfig;
import yunqi.courier.common.protocol.ProtocolMessage;
import yunqi.courier.kernel.spi.core.SPI;
import yunqi.courier.kernel.spi.network.NetworkServer;
import yunqi.courier.kernel.spi.network.RequestProcessor;
import yunqi.courier.kernel.spi.security.RequestAccessController;
import yunqi.courier.kernel.spi.core.ServiceProviderLoader;
import yunqi.courier.kernel.spi.serialization.Serializer;
import yunqi.courier.network.netty.handler.ServerHandler;
import yunqi.courier.network.netty.handler.YunqiProtocolDecoder;
import yunqi.courier.network.netty.handler.YunqiProtocolEncoder;
import yunqi.courier.network.netty.handler.HeartbeatHandler;
import yunqi.courier.network.netty.codec.PayloadTransformer;
import yunqi.courier.network.netty.ssl.TlsContextFactory;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.ChannelGroupFuture;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.netty.util.concurrent.GlobalEventExecutor;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@SPI("netty")
public class NettyNetworkServer implements NetworkServer {

    private EventLoopGroup bossGroup;

    private EventLoopGroup workerGroup;

    private Channel serverChannel;

    private volatile Serializer serializer;

    private int businessThreads = CourierConstants.DEFAULT_SERVER_BUSINESS_THREADS;

    private int queueCapacity = CourierConstants.DEFAULT_SERVER_QUEUE_CAPACITY;

    private ExecutorService businessExecutor;

    private volatile CourierConfig tlsConfig;

    private volatile SslContext tlsContext;

    private volatile PayloadTransformer payloadTransformer = PayloadTransformer.fromConfig(new CourierConfig());

    private volatile RequestAccessController accessController = RequestAccessController.ALLOW_ALL;

    private volatile int maxConnections = CourierConstants.DEFAULT_MAX_CONNECTIONS;

    private final AtomicBoolean acceptingRequests = new AtomicBoolean();

    private final AtomicInteger activeConnections = new AtomicInteger();

    private final ChannelGroup childChannels = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);

    private final Object connectionLifecycleLock = new Object();

    @Override
    public void configure(CourierConfig config) {
        this.serializer = ServiceProviderLoader.newInstance(Serializer.class, config.getSerialization());
        this.businessThreads = config.getServerBusinessThreads();
        this.queueCapacity = config.getServerQueueCapacity();
        this.maxConnections = config.getMaxConnections();
        this.tlsConfig = config.copy();
        this.tlsContext = null;
        this.payloadTransformer = PayloadTransformer.fromConfig(config);
        this.accessController = new yunqi.courier.kernel.security.DefaultRequestAccessController(config);
    }

    @Override
    public void start(String host, int port, RequestProcessor requestProcessor) {
        start(host, port, requestProcessor, CourierConstants.DEFAULT_MAX_FRAME_LENGTH);
    }

    @Override
    public synchronized void start(String host, int port, RequestProcessor requestProcessor, int maxFrameLength) {
        start(host, port, requestProcessor, maxFrameLength, accessController);
    }

    @Override
    public synchronized void start(String host, int port, RequestProcessor requestProcessor, int maxFrameLength,
                                   RequestAccessController accessController) {
        if (serverChannel != null && serverChannel.isOpen()) {
            return;
        }
        validateStartArguments(host, port, requestProcessor, maxFrameLength);
        this.accessController = accessController == null ? RequestAccessController.ALLOW_ALL : accessController;
        try {
            bossGroup = new NioEventLoopGroup(1, new DefaultThreadFactory("yunqi-courier-server-boss", false));
            workerGroup = new NioEventLoopGroup(
                    Math.min(16, Math.max(2, Runtime.getRuntime().availableProcessors())),
                    new DefaultThreadFactory("yunqi-courier-server-worker", false)
            );
            businessExecutor = new ThreadPoolExecutor(
                    businessThreads,
                    businessThreads,
                    0L,
                    TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(queueCapacity),
                    new DefaultThreadFactory("yunqi-courier-business", false),
                    new ThreadPoolExecutor.AbortPolicy()
            );
            if (serializer == null) {
                serializer = ServiceProviderLoader.load(Serializer.class, CourierConstants.DEFAULT_SERIALIZATION);
            }
            if (tlsEnabled()) {
                ensureTlsContext();
            }
            acceptingRequests.set(true);
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_BACKLOG, 1024)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            synchronized (connectionLifecycleLock) {
                                if (!acceptingRequests.get()) {
                                    ch.close();
                                    return;
                                }
                                int connections = activeConnections.incrementAndGet();
                                if (connections > maxConnections) {
                                    activeConnections.decrementAndGet();
                                    ch.close();
                                    return;
                                }
                                childChannels.add(ch);
                                ch.closeFuture().addListener(ignored -> {
                                    childChannels.remove(ch);
                                    activeConnections.decrementAndGet();
                                });
                            }
                            if (tlsEnabled()) {
                                SslHandler sslHandler = ensureTlsContext().newHandler(ch.alloc());
                                sslHandler.setHandshakeTimeoutMillis(tlsConfig.getConnectTimeoutMillis());
                                ch.pipeline().addLast("tls", sslHandler);
                                sslHandler.handshakeFuture().addListener(future -> {
                                    if (!future.isSuccess()) {
                                        ch.close();
                                    }
                                });
                            }
                            ch.pipeline()
                                    .addLast(new IdleStateHandler(30, 0, 0, TimeUnit.SECONDS))
                                    .addLast(new YunqiProtocolDecoder(maxFrameLength))
                                    .addLast(new YunqiProtocolEncoder(maxFrameLength))
                                    .addLast(new HeartbeatHandler())
                                    .addLast(new ServerHandler(requestProcessor, serializer, businessExecutor,
                                            NettyNetworkServer.this.accessController,
                                            acceptingRequests::get, payloadTransformer));
                        }
                    });
            serverChannel = bootstrap.bind(host, port).sync().channel();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            stop();
            throw new RemotingException("Start netty server interrupted, port=" + port, e);
        } catch (Exception e) {
            stop();
            throw new RemotingException("Start netty server failed, port=" + port, e);
        }
    }

    @Override
    public synchronized void stop() {
        Channel listeningChannel;
        synchronized (connectionLifecycleLock) {
            acceptingRequests.set(false);
            listeningChannel = serverChannel;
            serverChannel = null;
        }
        if (listeningChannel != null) {
            listeningChannel.close().syncUninterruptibly();
        }
        ChannelGroupFuture goAway = childChannels.writeAndFlush(ProtocolMessage.goAway("server stopping"));
        goAway.awaitUninterruptibly(1, TimeUnit.SECONDS);
        if (businessExecutor != null) {
            businessExecutor.shutdown();
            try {
                if (!businessExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    businessExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                businessExecutor.shutdownNow();
            }
            businessExecutor = null;
        }
        childChannels.close().awaitUninterruptibly(5, TimeUnit.SECONDS);
        if (workerGroup != null) {
            workerGroup.shutdownGracefully(0, 15, TimeUnit.SECONDS).syncUninterruptibly();
            workerGroup = null;
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully(0, 15, TimeUnit.SECONDS).syncUninterruptibly();
            bossGroup = null;
        }
        tlsContext = null;
    }

    @Override
    public synchronized void reloadTlsContext() {
        tlsContext = null;
        // Existing channels retain their negotiated session; newly accepted channels
        // use the rotated key/trust material.
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
            tlsContext = TlsContextFactory.createServerContext(tlsConfig);
        }
        return tlsContext;
    }

    private void validateStartArguments(String host, int port, RequestProcessor requestProcessor, int maxFrameLength) {
        if (host == null || host.isBlank() || !host.equals(host.trim())
                || host.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("host must not be blank, padded or contain control characters");
        }
        if (port < CourierConstants.MIN_PORT || port > CourierConstants.MAX_PORT) {
            throw new IllegalArgumentException("port must be between 1 and 65535");
        }
        if (requestProcessor == null) {
            throw new IllegalArgumentException("requestProcessor must not be null");
        }
        if (maxFrameLength < 1024 || maxFrameLength > CourierConstants.MAX_MAX_FRAME_LENGTH) {
            throw new IllegalArgumentException("maxFrameLength must be between 1024 and "
                    + CourierConstants.MAX_MAX_FRAME_LENGTH);
        }
    }
}
