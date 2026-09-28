package yunqi.courier.common.exception;

public class CourierException extends RuntimeException {

    public CourierException(String message) {
        super(message);
    }

    public CourierException(String message, Throwable cause) {
        super(message, cause);
    }
}
