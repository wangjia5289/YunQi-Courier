package yunqi.courier.common.protocol;

public enum ResponseCode {

    SUCCESS(200, "success"),

    BAD_REQUEST(400, "bad request"),

    UNAUTHORIZED(401, "unauthorized"),

    FORBIDDEN(403, "forbidden"),

    NOT_FOUND(404, "not found"),

    TIMEOUT(408, "timeout"),

    OVERLOADED(429, "overloaded"),

    FAILURE(500, "failure"),

    UNAVAILABLE(503, "unavailable"),

    APPLICATION_ERROR(520, "application error");

    private final int code;

    private final String message;

    ResponseCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public boolean retryableTransportFailure() {
        return this == TIMEOUT || this == OVERLOADED || this == UNAVAILABLE;
    }

    public static ResponseCode fromCode(int code) {
        for (ResponseCode value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        return FAILURE;
    }
}
