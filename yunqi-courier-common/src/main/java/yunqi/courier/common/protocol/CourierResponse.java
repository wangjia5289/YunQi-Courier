package yunqi.courier.common.protocol;

import java.io.Serializable;
import yunqi.courier.common.exception.AuthenticationException;
import yunqi.courier.common.exception.AuthorizationException;
import yunqi.courier.common.exception.OverloadException;
import yunqi.courier.common.exception.RequestValidationException;
import yunqi.courier.common.exception.ServiceNotFoundException;
import yunqi.courier.common.exception.ServiceUnavailableException;
import yunqi.courier.common.constant.CourierConstants;


public class CourierResponse implements Serializable {

    private String requestId;

    private int code;

    private String message;

    private Object data;

    private String errorClass;

    public static CourierResponse success(String requestId, Object data) {
        CourierResponse response = new CourierResponse();
        response.setRequestId(requestId);
        response.setCode(ResponseCode.SUCCESS.getCode());
        response.setMessage(ResponseCode.SUCCESS.getMessage());
        response.setData(data);
        return response;
    }

    public static CourierResponse failure(String requestId, Throwable throwable) {
        ResponseCode responseCode = classify(throwable);
        return failure(requestId, responseCode, throwable);
    }

    public static CourierResponse failure(String requestId, ResponseCode responseCode, Throwable throwable) {
        CourierResponse response = new CourierResponse();
        response.setRequestId(requestId);
        ResponseCode code = responseCode == null ? ResponseCode.FAILURE : responseCode;
        response.setCode(code.getCode());
        String message = throwable == null || throwable.getMessage() == null
                ? code.getMessage() : throwable.getMessage();
        response.setMessage(message.length() > CourierConstants.MAX_ERROR_MESSAGE_LENGTH
                ? message.substring(0, CourierConstants.MAX_ERROR_MESSAGE_LENGTH) : message);
        String errorClass = throwable == null ? null : throwable.getClass().getName();
        response.setErrorClass(errorClass == null || errorClass.length() <= CourierConstants.MAX_ERROR_MESSAGE_LENGTH
                ? errorClass : errorClass.substring(0, CourierConstants.MAX_ERROR_MESSAGE_LENGTH));
        return response;
    }

    public boolean retryableTransportFailure() {
        return ResponseCode.fromCode(code).retryableTransportFailure();
    }

    public ResponseCode responseCode() {
        return ResponseCode.fromCode(code);
    }

    public boolean success() {
        return code == ResponseCode.SUCCESS.getCode();
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public int getCode() {
        return code;
    }

    public void setCode(int code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public Object getData() {
        return data;
    }

    public void setData(Object data) {
        this.data = data;
    }

    public String getErrorClass() {
        return errorClass;
    }

    public void setErrorClass(String errorClass) {
        this.errorClass = errorClass;
    }

    private static ResponseCode classify(Throwable throwable) {
        if (throwable == null) {
            return ResponseCode.FAILURE;
        }
        if (throwable instanceof RequestValidationException) {
            return ResponseCode.BAD_REQUEST;
        }
        if (throwable instanceof AuthenticationException) {
            return ResponseCode.UNAUTHORIZED;
        }
        if (throwable instanceof AuthorizationException) {
            return ResponseCode.FORBIDDEN;
        }
        if (throwable instanceof ServiceNotFoundException) {
            return ResponseCode.NOT_FOUND;
        }
        if (throwable instanceof OverloadException) {
            return ResponseCode.OVERLOADED;
        }
        if (throwable instanceof ServiceUnavailableException) {
            return ResponseCode.UNAVAILABLE;
        }
        // Preserve the original generic 500 failure code for application
        // exceptions while using dedicated codes for policy/transport errors.
        return ResponseCode.FAILURE;
    }
}
