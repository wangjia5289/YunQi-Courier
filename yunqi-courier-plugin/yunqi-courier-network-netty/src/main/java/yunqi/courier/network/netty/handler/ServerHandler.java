package yunqi.courier.network.netty.handler;

import yunqi.courier.common.constant.CourierConstants;
import yunqi.courier.common.protocol.CourierRequest;
import yunqi.courier.common.protocol.CourierResponse;
import yunqi.courier.common.protocol.ProtocolMessage;
import yunqi.courier.common.protocol.ProtocolMessageType;
import yunqi.courier.common.protocol.CourierRequestValidator;
import yunqi.courier.common.exception.OverloadException;
import yunqi.courier.common.exception.RequestValidationException;
import yunqi.courier.common.exception.ServiceUnavailableException;
import yunqi.courier.api.context.RequestContext;
import yunqi.courier.kernel.spi.network.RequestProcessor;
import yunqi.courier.kernel.spi.security.RequestAccessController;
import yunqi.courier.kernel.spi.security.RequestSecurityContext;
import io.netty.handler.ssl.SslHandler;
import yunqi.courier.kernel.spi.serialization.Serializer;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import yunqi.courier.network.netty.codec.PayloadTransformer;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BooleanSupplier;

public class ServerHandler extends SimpleChannelInboundHandler<ProtocolMessage> {

    private final RequestProcessor requestProcessor;

    private final Serializer serializer;

    private final Executor businessExecutor;

    private final RequestAccessController accessController;

    private final BooleanSupplier acceptingRequests;

    private final PayloadTransformer payloadTransformer;

    public ServerHandler(RequestProcessor requestProcessor, Serializer serializer, Executor businessExecutor) {
        this(requestProcessor, serializer, businessExecutor, RequestAccessController.ALLOW_ALL, () -> true);
    }

    public ServerHandler(RequestProcessor requestProcessor, Serializer serializer, Executor businessExecutor,
                         RequestAccessController accessController) {
        this(requestProcessor, serializer, businessExecutor, accessController, () -> true);
    }

    public ServerHandler(RequestProcessor requestProcessor, Serializer serializer, Executor businessExecutor,
                         RequestAccessController accessController, BooleanSupplier acceptingRequests) {
        this(requestProcessor, serializer, businessExecutor, accessController, acceptingRequests,
                PayloadTransformer.fromConfig(new yunqi.courier.common.config.CourierConfig()));
    }

    public ServerHandler(RequestProcessor requestProcessor, Serializer serializer, Executor businessExecutor,
                         RequestAccessController accessController, BooleanSupplier acceptingRequests,
                         PayloadTransformer payloadTransformer) {
        this.requestProcessor = requestProcessor;
        this.serializer = serializer;
        this.businessExecutor = businessExecutor;
        this.accessController = accessController == null ? RequestAccessController.ALLOW_ALL : accessController;
        this.acceptingRequests = acceptingRequests == null ? () -> true : acceptingRequests;
        this.payloadTransformer = payloadTransformer;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ProtocolMessage msg) {
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
        if (msg.getMessageType() != ProtocolMessageType.REQUEST.getCode()
                || msg.getSerializationType() != serializer.code()) {
            ctx.close();
            return;
        }
        if (!acceptingRequests.getAsBoolean()) {
            writeResponse(ctx, msg.getRequestId(), CourierResponse.failure(Long.toString(msg.getRequestId()),
                    new ServiceUnavailableException("Server is draining and no longer accepts new requests")));
            return;
        }
        CourierRequest request;
        try {
            request = serializer.deserialize(payloadTransformer.decode(msg.getPayload()), CourierRequest.class);
        } catch (Exception throwable) {
            writeResponse(ctx, msg.getRequestId(), CourierResponse.failure(Long.toString(msg.getRequestId()),
                    new RequestValidationException("Invalid request payload: " + throwable.getMessage())));
            return;
        }
        if (request == null) {
            writeResponse(ctx, msg.getRequestId(), CourierResponse.failure(Long.toString(msg.getRequestId()),
                    new RequestValidationException("Request payload must not be null")));
            return;
        }
        try {
            if (!Long.toString(msg.getRequestId()).equals(request.getRequestId())) {
                writeResponse(ctx, msg.getRequestId(), CourierResponse.failure(request.getRequestId(),
                        new RequestValidationException("Request id mismatch")));
                return;
            }
            CourierRequestValidator.validate(request);
            javax.net.ssl.SSLSession sslSession = null;
            SslHandler sslHandler = ctx.pipeline().get(SslHandler.class);
            if (sslHandler != null && sslHandler.engine().getSession() != null) {
                sslSession = sslHandler.engine().getSession();
            }
            Object principal = accessController.check(request,
                    new RequestSecurityContext(ctx.channel().remoteAddress(), sslSession));
            businessExecutor.execute(() -> {
                try {
                    RequestContext.setAttachments(request.getAttachments());
                    RequestContext.setPrincipal(principal);
                    RequestContext.setTraceId(request.getTraceId());
                    CourierResponse response;
                    try {
                        response = requestProcessor.process(request);
                    } catch (Exception exception) {
                        response = CourierResponse.failure(request.getRequestId(), exception);
                    }
                    writeResponse(ctx, msg.getRequestId(), response);
                } finally {
                    RequestContext.clear();
                }
            });
        } catch (RejectedExecutionException e) {
            writeResponse(ctx, msg.getRequestId(), CourierResponse.failure(request.getRequestId(),
                    new OverloadException("Server business executor is overloaded")));
        } catch (RuntimeException e) {
            // Validation and access-control failures are protocol responses, not
            // transport failures; the client can then distinguish policy errors.
            writeResponse(ctx, msg.getRequestId(), CourierResponse.failure(request.getRequestId(), e));
        }
    }

    private void writeResponse(ChannelHandlerContext ctx, long requestId, CourierResponse response) {
        try {
            String expectedRequestId = Long.toString(requestId);
            if (response == null) {
                response = CourierResponse.failure(expectedRequestId,
                        new IllegalStateException("Request processor returned a null response"));
            } else if (!expectedRequestId.equals(response.getRequestId())) {
                response = CourierResponse.failure(expectedRequestId,
                        new IllegalStateException("Request processor returned a mismatched response requestId"));
            }
            ProtocolMessage responseMessage = new ProtocolMessage();
            responseMessage.setVersion(CourierConstants.PROTOCOL_VERSION);
            responseMessage.setMessageType(ProtocolMessageType.RESPONSE.getCode());
            responseMessage.setSerializationType(serializer.code());
            responseMessage.setRequestId(requestId);
            responseMessage.setPayload(payloadTransformer.encode(serializer.serialize(response)));
            ctx.writeAndFlush(responseMessage).addListener(future -> {
                if (!future.isSuccess()) {
                    ctx.close();
                }
            });
        } catch (Exception throwable) {
            // There is no reliable response format if serialization itself failed.
            // Closing the channel makes the client's pending call fail immediately.
            ctx.close();
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        ctx.close();
    }
}
