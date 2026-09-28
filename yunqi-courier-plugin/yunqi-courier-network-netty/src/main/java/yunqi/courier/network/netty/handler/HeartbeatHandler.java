package yunqi.courier.network.netty.handler;

import yunqi.courier.common.constant.CourierConstants;
import yunqi.courier.common.protocol.ProtocolMessage;
import yunqi.courier.common.protocol.ProtocolMessageType;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;

public class HeartbeatHandler extends ChannelDuplexHandler {

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object event) throws Exception {
        if (event instanceof IdleStateEvent idleStateEvent) {
            if (idleStateEvent.state() == IdleState.READER_IDLE) {
                ctx.close();
                return;
            }
            if (idleStateEvent.state() == IdleState.WRITER_IDLE) {
                ProtocolMessage heartbeat = new ProtocolMessage();
                heartbeat.setVersion(CourierConstants.PROTOCOL_VERSION);
                heartbeat.setMessageType(ProtocolMessageType.HEARTBEAT.getCode());
                heartbeat.setRequestId(0);
                ctx.writeAndFlush(heartbeat).addListener(future -> {
                    if (!future.isSuccess()) {
                        ctx.close();
                    }
                });
                return;
            }
        }
        super.userEventTriggered(ctx, event);
    }
}
