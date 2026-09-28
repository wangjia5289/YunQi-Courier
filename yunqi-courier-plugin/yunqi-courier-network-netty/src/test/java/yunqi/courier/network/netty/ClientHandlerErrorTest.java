package yunqi.courier.network.netty;

import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;
import yunqi.courier.common.exception.SerializeException;
import yunqi.courier.common.protocol.CourierResponse;
import yunqi.courier.common.protocol.ProtocolMessage;
import yunqi.courier.common.protocol.ProtocolMessageType;
import yunqi.courier.kernel.spi.serialization.Serializer;
import yunqi.courier.network.netty.client.PendingRequestManager;
import yunqi.courier.network.netty.handler.ClientHandler;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientHandlerErrorTest {

    @Test
    void shouldCompletePendingFutureWhenResponseDeserializationFails() {
        PendingRequestManager pending = new PendingRequestManager();
        StubSerializer serializer = new StubSerializer();
        EmbeddedChannel channel = new EmbeddedChannel(new ClientHandler(pending, serializer));
        CompletableFuture<CourierResponse> future = new CompletableFuture<>();
        pending.put(9L, future, channel);

        channel.writeInbound(responseMessage(9));

        assertTrue(future.isCompletedExceptionally());
        assertTrue(!channel.isActive());
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldCompletePendingFutureWhenResponseIsNull() {
        PendingRequestManager pending = new PendingRequestManager();
        StubSerializer serializer = new StubSerializer();
        serializer.returnNull = true;
        EmbeddedChannel channel = new EmbeddedChannel(new ClientHandler(pending, serializer));
        CompletableFuture<CourierResponse> future = new CompletableFuture<>();
        pending.put(10L, future, channel);

        channel.writeInbound(responseMessage(10));

        assertTrue(future.isCompletedExceptionally());
        assertTrue(!channel.isActive());
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldFailPendingRequestsWhenServerSendsGoAway() {
        PendingRequestManager pending = new PendingRequestManager();
        StubSerializer serializer = new StubSerializer();
        EmbeddedChannel channel = new EmbeddedChannel(new ClientHandler(pending, serializer));
        CompletableFuture<CourierResponse> future = new CompletableFuture<>();
        pending.put(11L, future, channel);

        channel.writeInbound(ProtocolMessage.goAway("planned maintenance"));

        assertTrue(future.isCompletedExceptionally());
        assertTrue(pending.size() == 0);
        assertTrue(!channel.isActive());
        channel.finishAndReleaseAll();
    }

    private static ProtocolMessage responseMessage(long requestId) {
        ProtocolMessage message = new ProtocolMessage();
        message.setVersion((byte) 1);
        message.setMessageType(ProtocolMessageType.RESPONSE.getCode());
        message.setSerializationType((byte) 1);
        message.setRequestId(requestId);
        message.setPayload(new byte[]{1});
        return message;
    }

    private static final class StubSerializer implements Serializer {
        private boolean returnNull;

        @Override
        public byte code() {
            return 1;
        }

        @Override
        public byte[] serialize(Object object) {
            return new byte[]{1};
        }

        @Override
        public <T> T deserialize(byte[] bytes, Class<T> targetClass) {
            if (returnNull) {
                return null;
            }
            throw new SerializeException("response deserialization failed", new IllegalStateException());
        }
    }
}
