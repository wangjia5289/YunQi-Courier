package yunqi.courier.network.netty.handler;

import yunqi.courier.common.constant.CourierConstants;
import yunqi.courier.common.protocol.ProtocolMessage;
import yunqi.courier.common.protocol.ProtocolMessageType;
import yunqi.courier.network.netty.protocol.YunqiProtocolCodec;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.util.List;

public class YunqiProtocolDecoder extends ByteToMessageDecoder {

    private final int maxFrameLength;

    public YunqiProtocolDecoder() {
        this(CourierConstants.DEFAULT_MAX_FRAME_LENGTH);
    }

    public YunqiProtocolDecoder(int maxFrameLength) {
        if (maxFrameLength < 1024 || maxFrameLength > CourierConstants.MAX_MAX_FRAME_LENGTH) {
            throw new IllegalArgumentException("maxFrameLength must be between 1024 and "
                    + CourierConstants.MAX_MAX_FRAME_LENGTH);
        }
        this.maxFrameLength = maxFrameLength;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (in.readableBytes() < YunqiProtocolCodec.HEADER_LENGTH) {
            return;
        }
        in.markReaderIndex();
        int magic = in.readInt();
        if (magic != CourierConstants.PROTOCOL_MAGIC) {
            ctx.close();
            return;
        }
        byte version = in.readByte();
        byte messageType = in.readByte();
        byte serializationType = in.readByte();
        long requestId = in.readLong();
        int payloadLength = in.readInt();
        if (version != CourierConstants.PROTOCOL_VERSION || !ProtocolMessageType.isSupported(messageType)) {
            ctx.close();
            return;
        }
        if (!validRequestId(messageType, requestId)) {
            ctx.close();
            return;
        }
        if (payloadLength < 0 || payloadLength > maxFrameLength) {
            ctx.close();
            return;
        }
        if (in.readableBytes() < payloadLength) {
            in.resetReaderIndex();
            return;
        }
        byte[] payload = new byte[payloadLength];
        in.readBytes(payload);

        ProtocolMessage protocolMessage = new ProtocolMessage();
        protocolMessage.setVersion(version);
        protocolMessage.setMessageType(messageType);
        protocolMessage.setSerializationType(serializationType);
        protocolMessage.setRequestId(requestId);
        protocolMessage.setPayload(payload);
        out.add(protocolMessage);
    }

    private boolean validRequestId(byte messageType, long requestId) {
        if (messageType == ProtocolMessageType.REQUEST.getCode()
                || messageType == ProtocolMessageType.RESPONSE.getCode()) {
            return requestId > 0;
        }
        return requestId == 0;
    }
}
