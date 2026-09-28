package yunqi.courier.network.netty;

import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.EncoderException;
import org.junit.jupiter.api.Test;
import yunqi.courier.common.constant.CourierConstants;
import yunqi.courier.common.protocol.ProtocolMessage;
import yunqi.courier.common.protocol.ProtocolMessageType;
import yunqi.courier.network.netty.handler.YunqiProtocolEncoder;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class YunqiProtocolEncoderTest {

    @Test
    void shouldEncodeGoAwayAsAControlFrame() {
        EmbeddedChannel channel = new EmbeddedChannel(new YunqiProtocolEncoder(1024));

        channel.writeOutbound(ProtocolMessage.goAway("planned maintenance"));

        ByteBuf frame = channel.readOutbound();
        assertEquals(CourierConstants.PROTOCOL_MAGIC, frame.readInt());
        assertEquals(CourierConstants.PROTOCOL_VERSION, frame.readByte());
        assertEquals(ProtocolMessageType.GO_AWAY.getCode(), frame.readByte());
        assertEquals(0, frame.readByte());
        assertEquals(0, frame.readLong());
        int payloadLength = frame.readInt();
        byte[] payload = new byte[payloadLength];
        frame.readBytes(payload);
        assertEquals("planned maintenance", new String(payload, StandardCharsets.UTF_8));
        frame.release();
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldRejectGoAwayWithNonZeroRequestId() {
        EmbeddedChannel channel = new EmbeddedChannel(new YunqiProtocolEncoder(1024));
        ProtocolMessage message = ProtocolMessage.goAway("invalid");
        message.setRequestId(1);

        EncoderException failure = assertThrows(EncoderException.class, () -> channel.writeOutbound(message));
        assertEquals(IllegalArgumentException.class, failure.getCause().getClass());
        channel.finishAndReleaseAll();
    }
}
