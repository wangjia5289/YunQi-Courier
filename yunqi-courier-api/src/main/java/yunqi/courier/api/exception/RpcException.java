package yunqi.courier.api.exception;

import yunqi.courier.common.exception.CourierException;
import yunqi.courier.common.protocol.ResponseCode;

public class RpcException extends CourierException {

    private final ResponseCode responseCode;

    private final String remoteErrorClass;

    public RpcException(String message) {
        this(null, message, null);
    }

    public RpcException(String message, Throwable cause) {
        this(null, message, cause);
    }

    public RpcException(ResponseCode responseCode, String message) {
        this(responseCode, message, null);
    }

    public RpcException(ResponseCode responseCode, String message, Throwable cause) {
        this(responseCode, message, cause, null);
    }

    public RpcException(ResponseCode responseCode, String message, Throwable cause, String remoteErrorClass) {
        super(message, cause);
        this.responseCode = responseCode;
        this.remoteErrorClass = remoteErrorClass;
    }

    /** Returns the remote protocol code when this exception came from a response. */
    public ResponseCode getResponseCode() {
        return responseCode;
    }

    /** Returns the provider-side exception class when it was included in the response. */
    public String getRemoteErrorClass() {
        return remoteErrorClass;
    }

    /** Returns whether the associated protocol code is classified as transport-retryable. */
    public boolean isRetryable() {
        return responseCode != null && responseCode.retryableTransportFailure();
    }
}
