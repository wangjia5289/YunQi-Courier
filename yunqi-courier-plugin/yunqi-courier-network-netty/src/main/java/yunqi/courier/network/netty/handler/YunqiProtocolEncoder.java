package yunqi.courier.network.netty.handler;

import yunqi.courier.common.constant.CourierConstants;
import yunqi.courier.common.protocol.ProtocolMessage;
import yunqi.courier.common.protocol.ProtocolMessageType;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

public class YunqiProtocolEncoder extends MessageToByteEncoder<ProtocolMessage> {

    private final int maxFrameLength;

    public YunqiProtocolEncoder() {
        this(CourierConstants.DEFAULT_MAX_FRAME_LENGTH);
    }

    public YunqiProtocolEncoder(int maxFrameLength) {
        if (maxFrameLength < 1024 || maxFrameLength > CourierConstants.MAX_MAX_FRAME_LENGTH) {
            throw new IllegalArgumentException("maxFrameLength must be between 1024 and "
                    + CourierConstants.MAX_MAX_FRAME_LENGTH);
        }
        this.maxFrameLength = maxFrameLength;
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, ProtocolMessage msg, ByteBuf out) {
        if (msg.getVersion() != CourierConstants.PROTOCOL_VERSION
                || !ProtocolMessageType.isSupported(msg.getMessageType())) {
            throw new IllegalArgumentException("Unsupported protocol message");
        }
        if ((msg.getMessageType() == ProtocolMessageType.REQUEST.getCode()
                || msg.getMessageType() == ProtocolMessageType.RESPONSE.getCode())
                && msg.getRequestId() <= 0) {
            throw new IllegalArgumentException("Request/response requestId must be positive");
        }
        if ((msg.getMessageType() == ProtocolMessageType.HEARTBEAT.getCode()
                || msg.getMessageType() == ProtocolMessageType.HEARTBEAT_ACK.getCode()
                || msg.getMessageType() == ProtocolMessageType.GO_AWAY.getCode())
                && msg.getRequestId() != 0) {
            throw new IllegalArgumentException("Control message requestId must be zero");
        }
        byte[] payload = msg.getPayload() == null ? new byte[0] : msg.getPayload();
        if (payload.length > maxFrameLength) {
            throw new IllegalArgumentException("Payload exceeds maximum frame length: " + payload.length);
        }
        out.writeInt(CourierConstants.PROTOCOL_MAGIC);
        out.writeByte(msg.getVersion());
        out.writeByte(msg.getMessageType());
        out.writeByte(msg.getSerializationType());
        out.writeLong(msg.getRequestId());
        out.writeInt(payload.length);
        out.writeBytes(payload);
    }
}
