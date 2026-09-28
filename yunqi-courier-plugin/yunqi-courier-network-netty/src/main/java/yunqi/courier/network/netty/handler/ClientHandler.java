package yunqi.courier.network.netty.handler;

import yunqi.courier.common.protocol.CourierResponse;
import yunqi.courier.common.protocol.ProtocolMessage;
import yunqi.courier.common.protocol.ProtocolMessageType;
import yunqi.courier.common.constant.CourierConstants;
import yunqi.courier.common.exception.RemotingException;
import yunqi.courier.kernel.spi.serialization.Serializer;
import yunqi.courier.network.netty.client.PendingRequestManager;
import yunqi.courier.network.netty.codec.PayloadTransformer;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

import java.nio.charset.StandardCharsets;

public class ClientHandler extends SimpleChannelInboundHandler<ProtocolMessage> {

    private final PendingRequestManager pendingRequestManager;

    private final Serializer serializer;

    private final PayloadTransformer payloadTransformer;

    public ClientHandler(PendingRequestManager pendingRequestManager, Serializer serializer) {
        this(pendingRequestManager, serializer, PayloadTransformer.fromConfig(new yunqi.courier.common.config.CourierConfig()));
    }

    public ClientHandler(PendingRequestManager pendingRequestManager, Serializer serializer,
                         PayloadTransformer payloadTransformer) {
        this.pendingRequestManager = pendingRequestManager;
        this.serializer = serializer;
        this.payloadTransformer = payloadTransformer;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ProtocolMessage msg) {
        if (msg.getMessageType() == ProtocolMessageType.GO_AWAY.getCode()) {
            byte[] payload = msg.getPayload();
            int reasonLength = payload == null ? 0 :
                    Math.min(payload.length, CourierConstants.MAX_ERROR_MESSAGE_LENGTH);
            String reason = reasonLength == 0 ? "" :
                    new String(payload, 0, reasonLength, StandardCharsets.UTF_8);
            String message = reason.isBlank() ? "RPC server is draining" :
                    "RPC server is draining: " + reason;
            pendingRequestManager.fail(ctx.channel(), new RemotingException(message));
            ctx.close();
            return;
        }
        if (msg.getMessageType() == ProtocolMessageType.HEARTBEAT.getCode()) {
            ProtocolMessage acknowledgement = new ProtocolMessage();
            acknowledgement.setVersion(msg.getVersion());
            acknowledgement.setMessageType(ProtocolMessageType.HEARTBEAT_ACK.getCode());
            acknowledgement.setRequestId(msg.getRequestId());
            ctx.writeAndFlush(acknowledgement).addListener(future -> {
                if (!future.isSuccess()) {
                    ctx.close();
                }
            });
            return;
        }
        if (msg.getMessageType() == ProtocolMessageType.HEARTBEAT_ACK.getCode()) {
            return;
        }
        if (msg.getMessageType() != ProtocolMessageType.RESPONSE.getCode()
                || msg.getSerializationType() != serializer.code()) {
            ctx.close();
            return;
        }
        CourierResponse response = serializer.deserialize(payloadTransformer.decode(msg.getPayload()), CourierResponse.class);
        if (response == null) {
            throw new IllegalStateException("RPC response payload must not be null");
        }
        if (response.getRequestId() == null) {
            throw new IllegalStateException("RPC response requestId must not be null");
        }
        if (response.getRequestId() != null && !response.getRequestId().equals(Long.toString(msg.getRequestId()))) {
            pendingRequestManager.fail(ctx.channel(), new IllegalStateException("Response requestId mismatch"));
            ctx.close();
            return;
        }
        var future = pendingRequestManager.remove(msg.getRequestId());
        if (future != null) {
            future.complete(response);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        pendingRequestManager.fail(ctx.channel(), new RemotingException("RPC channel failure", cause));
        ctx.close();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        pendingRequestManager.fail(ctx.channel(),
                new RemotingException("RPC channel became inactive"));
    }
}
