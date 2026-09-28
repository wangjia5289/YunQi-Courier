package yunqi.courier.network.netty;

import yunqi.courier.common.constant.CourierConstants;
import yunqi.courier.common.protocol.ProtocolMessageType;
import yunqi.courier.network.netty.handler.YunqiProtocolDecoder;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class YunqiProtocolDecoderTest {

    @Test
    void shouldCloseChannelWhenFrameExceedsConfiguredLimit() {
        EmbeddedChannel channel = new EmbeddedChannel(new YunqiProtocolDecoder(1024));
        channel.writeInbound(frame(CourierConstants.PROTOCOL_MAGIC, CourierConstants.PROTOCOL_VERSION,
                ProtocolMessageType.REQUEST.getCode(), 1, 2048));
        assertFalse(channel.isActive());
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldCloseChannelWhenMagicIsInvalid() {
        assertInvalidHeader(0x12345678, CourierConstants.PROTOCOL_VERSION,
                ProtocolMessageType.REQUEST.getCode(), 1, 0);
    }

    @Test
    void shouldCloseChannelWhenVersionIsUnsupported() {
        assertInvalidHeader(CourierConstants.PROTOCOL_MAGIC, (byte) (CourierConstants.PROTOCOL_VERSION + 1),
                ProtocolMessageType.REQUEST.getCode(), 1, 0);
    }

    @Test
    void shouldCloseChannelWhenMessageTypeIsUnsupported() {
        assertInvalidHeader(CourierConstants.PROTOCOL_MAGIC, CourierConstants.PROTOCOL_VERSION,
                (byte) 99, 1, 0);
    }

    @Test
    void shouldCloseChannelWhenPayloadLengthIsNegative() {
        assertInvalidHeader(CourierConstants.PROTOCOL_MAGIC, CourierConstants.PROTOCOL_VERSION,
                ProtocolMessageType.REQUEST.getCode(), 1, -1);
    }

    @Test
    void shouldCloseChannelWhenRequestIdIsNotPositive() {
        assertInvalidHeader(CourierConstants.PROTOCOL_MAGIC, CourierConstants.PROTOCOL_VERSION,
                ProtocolMessageType.REQUEST.getCode(), 0, 0);
    }

    @Test
    void shouldCloseChannelWhenHeartbeatIdIsNotZero() {
        assertInvalidHeader(CourierConstants.PROTOCOL_MAGIC, CourierConstants.PROTOCOL_VERSION,
                ProtocolMessageType.HEARTBEAT.getCode(), 1, 0);
    }

    @Test
    void shouldRejectUnsafeConfiguredFrameLimit() {
        assertThrows(IllegalArgumentException.class,
                () -> new YunqiProtocolDecoder(CourierConstants.MAX_MAX_FRAME_LENGTH + 1));
    }

    private static void assertInvalidHeader(int magic, byte version, byte messageType,
                                             long requestId, int payloadLength) {
        EmbeddedChannel channel = new EmbeddedChannel(new YunqiProtocolDecoder(1024));
        channel.writeInbound(frame(magic, version, messageType, requestId, payloadLength));
        assertFalse(channel.isActive());
        channel.finishAndReleaseAll();
    }

    private static ByteBuf frame(int magic, byte version, byte messageType, long requestId, int payloadLength) {
        ByteBuf frame = Unpooled.buffer(19);
        frame.writeInt(magic)
                .writeByte(version)
                .writeByte(messageType)
                .writeByte(1)
                .writeLong(requestId)
                .writeInt(payloadLength);
        return frame;
    }
}
