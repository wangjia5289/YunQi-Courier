package yunqi.courier.common.protocol;

public enum ProtocolMessageType {

    REQUEST((byte) 1),

    RESPONSE((byte) 2),

    HEARTBEAT((byte) 3),

    HEARTBEAT_ACK((byte) 4),

    /** Server drain notification with an optional UTF-8 reason payload. */
    GO_AWAY((byte) 5);

    private final byte code;

    ProtocolMessageType(byte code) {
        this.code = code;
    }

    public byte getCode() {
        return code;
    }

    public static boolean isSupported(byte value) {
        for (ProtocolMessageType type : values()) {
            if (type.code == value) {
                return true;
            }
        }
        return false;
    }
}
