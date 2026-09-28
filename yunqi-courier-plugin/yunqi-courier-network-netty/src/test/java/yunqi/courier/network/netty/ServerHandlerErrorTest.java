package yunqi.courier.network.netty;

import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;
import yunqi.courier.common.exception.SerializeException;
import yunqi.courier.common.exception.AuthenticationException;
import yunqi.courier.common.protocol.CourierRequest;
import yunqi.courier.common.protocol.CourierResponse;
import yunqi.courier.common.protocol.ProtocolMessage;
import yunqi.courier.common.protocol.ProtocolMessageType;
import yunqi.courier.kernel.spi.network.RequestProcessor;
import yunqi.courier.kernel.spi.serialization.Serializer;
import yunqi.courier.kernel.spi.security.RequestAccessController;
import yunqi.courier.network.netty.handler.ServerHandler;

import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ServerHandlerErrorTest {

    private static final Executor DIRECT_EXECUTOR = Runnable::run;

    @Test
    void shouldReturnFailureResponseWhenBusinessProcessorThrows() {
        StubSerializer serializer = new StubSerializer();
        RequestProcessor processor = request -> {
            throw new IllegalStateException("business failed");
        };
        EmbeddedChannel channel = new EmbeddedChannel(new ServerHandler(processor, serializer, DIRECT_EXECUTOR));

        channel.writeInbound(requestMessage(7));

        ProtocolMessage responseMessage = channel.readOutbound();
        assertNotNull(responseMessage);
        CourierResponse response = serializer.response;
        assertNotNull(response);
        assertFalse(response.success());
        assertTrue(response.getMessage().contains("business failed"));
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldCloseChannelWhenResponseSerializationFails() {
        StubSerializer serializer = new StubSerializer();
        serializer.failResponseSerialization = true;
        EmbeddedChannel channel = new EmbeddedChannel(
                new ServerHandler(request -> CourierResponse.success("7", "ok"), serializer, DIRECT_EXECUTOR));

        channel.writeInbound(requestMessage(7));

        assertFalse(channel.isActive());
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldReturnFailureResponseWhenBusinessProcessorReturnsNull() {
        StubSerializer serializer = new StubSerializer();
        EmbeddedChannel channel = new EmbeddedChannel(
                new ServerHandler(request -> null, serializer, DIRECT_EXECUTOR));

        channel.writeInbound(requestMessage(7));

        ProtocolMessage responseMessage = channel.readOutbound();
        assertNotNull(responseMessage);
        assertNotNull(serializer.response);
        assertFalse(serializer.response.success());
        assertTrue(serializer.response.getMessage().contains("null response"));
        assertTrue("7".equals(serializer.response.getRequestId()));
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldReturnUnauthorizedWithoutUsingBusinessExecutor() {
        StubSerializer serializer = new StubSerializer();
        int[] executions = {0};
        RequestAccessController deny = new RequestAccessController() {
            @Override
            public Object authenticate(CourierRequest request, java.net.SocketAddress remoteAddress) {
                throw new AuthenticationException("missing credentials");
            }

            @Override
            public void authorize(Object principal, CourierRequest request) {
            }
        };
        EmbeddedChannel channel = new EmbeddedChannel(new ServerHandler(request -> {
            executions[0]++;
            return CourierResponse.success(request.getRequestId(), "unexpected");
        }, serializer, command -> executions[0]++, deny));

        channel.writeInbound(requestMessage(7));

        assertEquals(0, executions[0]);
        assertEquals(401, serializer.response.getCode());
        assertTrue(serializer.response.getMessage().contains("missing credentials"));
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldRejectNewRequestsWhileDraining() {
        StubSerializer serializer = new StubSerializer();
        int[] executions = {0};
        EmbeddedChannel channel = new EmbeddedChannel(new ServerHandler(request -> {
            executions[0]++;
            return CourierResponse.success(request.getRequestId(), "unexpected");
        }, serializer, DIRECT_EXECUTOR, RequestAccessController.ALLOW_ALL, () -> false));

        channel.writeInbound(requestMessage(7));

        assertEquals(0, executions[0]);
        assertNotNull(serializer.response);
        assertEquals(503, serializer.response.getCode());
        assertTrue(serializer.response.getMessage().contains("draining"));
        channel.finishAndReleaseAll();
    }

    private static ProtocolMessage requestMessage(long requestId) {
        ProtocolMessage message = new ProtocolMessage();
        message.setVersion((byte) 1);
        message.setMessageType(ProtocolMessageType.REQUEST.getCode());
        message.setSerializationType((byte) 1);
        message.setRequestId(requestId);
        message.setPayload(new byte[]{1});
        return message;
    }

    private static final class StubSerializer implements Serializer {
        private CourierResponse response;
        private boolean failResponseSerialization;

        @Override
        public byte code() {
            return 1;
        }

        @Override
        public byte[] serialize(Object object) {
            if (object instanceof CourierResponse courierResponse) {
                response = courierResponse;
                if (failResponseSerialization) {
                    throw new SerializeException("response serialization failed", new IllegalStateException());
                }
            }
            return new byte[]{1};
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T deserialize(byte[] bytes, Class<T> targetClass) {
            CourierRequest request = new CourierRequest();
            request.setRequestId("7");
            request.setServiceName("test.Service:default:1.0.0");
            request.setMethodName("call");
            request.setParameterTypeNames(new String[0]);
            request.setParameters(new Object[0]);
            return (T) request;
        }
    }
}
