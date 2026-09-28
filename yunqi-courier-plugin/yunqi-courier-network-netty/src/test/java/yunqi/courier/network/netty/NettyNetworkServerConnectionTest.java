package yunqi.courier.network.netty;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.junit.jupiter.api.Test;
import yunqi.courier.common.config.CourierConfig;
import yunqi.courier.common.protocol.ProtocolMessage;
import yunqi.courier.common.protocol.ProtocolMessageType;
import yunqi.courier.common.protocol.CourierRequest;
import yunqi.courier.common.protocol.CourierResponse;
import yunqi.courier.common.service.ServiceEndpoint;
import yunqi.courier.kernel.spi.network.NetworkClient;
import yunqi.courier.network.netty.client.NettyNetworkClient;
import yunqi.courier.network.netty.handler.YunqiProtocolDecoder;
import yunqi.courier.network.netty.server.NettyNetworkServer;

import java.net.ServerSocket;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class NettyNetworkServerConnectionTest {

    @Test
    void shouldCloseConnectionsAboveConfiguredLimit() throws Exception {
        int port = availablePort();
        CourierConfig config = new CourierConfig();
        config.setPort(port);
        config.setMaxConnections(1);
        NettyNetworkServer server = new NettyNetworkServer();
        EventLoopGroup clientGroup = new NioEventLoopGroup(1);
        Channel first = null;
        Channel second = null;
        CountDownLatch goAwayReceived = new CountDownLatch(1);
        AtomicReference<ProtocolMessage> goAwayMessage = new AtomicReference<>();
        try {
            server.configure(config);
            server.start("127.0.0.1", port, request -> null);
            Bootstrap bootstrap = new Bootstrap()
                    .group(clientGroup)
                    .channel(NioSocketChannel.class)
                    .handler(new ChannelInitializer<Channel>() {
                        @Override
                        protected void initChannel(Channel channel) {
                            channel.pipeline()
                                    .addLast(new YunqiProtocolDecoder())
                                    .addLast(new SimpleChannelInboundHandler<ProtocolMessage>() {
                                        @Override
                                        protected void channelRead0(io.netty.channel.ChannelHandlerContext ctx,
                                                                    ProtocolMessage message) {
                                            if (message.getMessageType() == ProtocolMessageType.GO_AWAY.getCode()) {
                                                goAwayMessage.set(message);
                                                goAwayReceived.countDown();
                                            }
                                        }
                                    });
                        }
                    });

            first = bootstrap.connect("127.0.0.1", port).sync().channel();
            second = bootstrap.connect("127.0.0.1", port).sync().channel();

            assertTrue(first.isActive());
            assertTrue(second.closeFuture().await(2, TimeUnit.SECONDS));
            assertTrue(!second.isActive());
            server.stop();
            assertTrue(goAwayReceived.await(2, TimeUnit.SECONDS));
            assertTrue(goAwayMessage.get() != null);
            assertTrue(goAwayMessage.get().getMessageType() == ProtocolMessageType.GO_AWAY.getCode());
        } finally {
            if (second != null) {
                second.close().syncUninterruptibly();
            }
            if (first != null) {
                first.close().syncUninterruptibly();
            }
            server.stop();
            clientGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS).syncUninterruptibly();
        }
    }

    @Test
    void shouldReconnectPreviouslyUsedEndpointInBackground() throws Exception {
        int port = availablePort();
        CourierConfig config = new CourierConfig();
        config.setPort(port);
        config.setConnectTimeoutMillis(200);
        config.setClientReconnectBaseDelayMillis(20);
        config.setClientReconnectMaxDelayMillis(100);
        NettyNetworkServer server = new NettyNetworkServer();
        NetworkClient client = new NettyNetworkClient();
        ServiceEndpoint endpoint = new ServiceEndpoint("127.0.0.1", port);
        try {
            server.configure(config);
            server.start("127.0.0.1", port,
                    request -> CourierResponse.success(request.getRequestId(), "ok"));
            client.configure(config);

            CourierRequest request = new CourierRequest();
            request.setRequestId("1");
            request.setServiceName("reconnect-test");
            request.setMethodName("ping");
            request.setParameterTypeNames(new String[0]);
            request.setParameters(new Object[0]);
            assertTrue(client.sendRequest(request, endpoint, 1_000).get(2, TimeUnit.SECONDS).success());

            server.stop();
            server.start("127.0.0.1", port,
                    ignored -> CourierResponse.success("2", "ok"));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!hasActiveChannel(client, endpoint) && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertTrue(hasActiveChannel(client, endpoint), "client did not reconnect the endpoint in background");
        } finally {
            client.close();
            server.stop();
        }
    }

    @SuppressWarnings("unchecked")
    private static boolean hasActiveChannel(NetworkClient client, ServiceEndpoint endpoint) throws Exception {
        if (!(client instanceof NettyNetworkClient nettyClient)) {
            return false;
        }
        var field = NettyNetworkClient.class.getDeclaredField("channelMap");
        field.setAccessible(true);
        Map<String, Channel> channels = (Map<String, Channel>) field.get(nettyClient);
        Channel channel = channels.get(endpoint.address());
        return channel != null && channel.isActive();
    }

    private static int availablePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
