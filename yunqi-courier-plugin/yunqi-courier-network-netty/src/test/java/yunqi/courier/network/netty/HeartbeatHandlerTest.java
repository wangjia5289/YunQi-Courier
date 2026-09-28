package yunqi.courier.network.netty;

import yunqi.courier.common.protocol.ProtocolMessage;
import yunqi.courier.common.protocol.ProtocolMessageType;
import yunqi.courier.common.constant.CourierConstants;
import yunqi.courier.network.netty.handler.HeartbeatHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.timeout.IdleStateEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class HeartbeatHandlerTest {

    @Test
    void shouldEmitSingleHeartbeatWithoutEchoLoop() {
        EmbeddedChannel channel = new EmbeddedChannel(new HeartbeatHandler());
        channel.pipeline().fireUserEventTriggered(IdleStateEvent.WRITER_IDLE_STATE_EVENT);
        ProtocolMessage acknowledgement = channel.readOutbound();
        assertNotNull(acknowledgement);
        assertEquals(ProtocolMessageType.HEARTBEAT.getCode(), acknowledgement.getMessageType());
        assertEquals(CourierConstants.PROTOCOL_VERSION, acknowledgement.getVersion());
        assertNull(channel.readOutbound());
        channel.finishAndReleaseAll();
    }
}
