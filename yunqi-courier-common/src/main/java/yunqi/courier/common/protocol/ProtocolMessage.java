package yunqi.courier.common.protocol;

import java.nio.charset.StandardCharsets;

public class ProtocolMessage {

    private byte version;

    private byte messageType;

    private byte serializationType;

    private long requestId;

    private byte[] payload;

    public static ProtocolMessage heartbeat() {
        ProtocolMessage message = new ProtocolMessage();
        message.version = yunqi.courier.common.constant.CourierConstants.PROTOCOL_VERSION;
        message.messageType = ProtocolMessageType.HEARTBEAT.getCode();
        return message;
    }

    public static ProtocolMessage goAway(String reason) {
        ProtocolMessage message = new ProtocolMessage();
        message.version = yunqi.courier.common.constant.CourierConstants.PROTOCOL_VERSION;
        message.messageType = ProtocolMessageType.GO_AWAY.getCode();
        message.payload = reason == null ? new byte[0] : reason.getBytes(StandardCharsets.UTF_8);
        return message;
    }

    public byte getVersion() {
        return version;
    }

    public void setVersion(byte version) {
        this.version = version;
    }

    public byte getMessageType() {
        return messageType;
    }

    public void setMessageType(byte messageType) {
        this.messageType = messageType;
    }

    public byte getSerializationType() {
        return serializationType;
    }

    public void setSerializationType(byte serializationType) {
        this.serializationType = serializationType;
    }

    public long getRequestId() {
        return requestId;
    }

    public void setRequestId(long requestId) {
        this.requestId = requestId;
    }

    public byte[] getPayload() {
        return payload;
    }

    public void setPayload(byte[] payload) {
        this.payload = payload;
    }
}
