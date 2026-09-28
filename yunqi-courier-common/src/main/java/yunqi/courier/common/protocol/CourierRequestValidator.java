package yunqi.courier.common.protocol;

import yunqi.courier.common.constant.CourierConstants;
import yunqi.courier.common.exception.RequestValidationException;

import java.util.Map;

/** Validates untrusted request metadata before reflection or queue submission. */
public final class CourierRequestValidator {

    private CourierRequestValidator() {
    }

    public static void validate(CourierRequest request) {
        if (request == null) {
            throw new RequestValidationException("Request must not be null");
        }
        requirePositiveRequestId(request.getRequestId());
        if (request.getAuthenticationToken() != null
                && request.getAuthenticationToken().length() > CourierConstants.MAX_REQUEST_METADATA_STRING_LENGTH) {
            throw new RequestValidationException("authenticationToken exceeds "
                    + CourierConstants.MAX_REQUEST_METADATA_STRING_LENGTH + " characters");
        }
        if (request.getTraceId() != null && request.getTraceId().length()
                > CourierConstants.MAX_REQUEST_METADATA_STRING_LENGTH) {
            throw new RequestValidationException("traceId exceeds "
                    + CourierConstants.MAX_REQUEST_METADATA_STRING_LENGTH + " characters");
        }
        requireBoundedText(request.getServiceName(), "serviceName");
        requireBoundedText(request.getMethodName(), "methodName");
        String[] parameterTypeNames = request.getParameterTypeNames();
        Object[] parameters = request.getParameters();
        if (parameterTypeNames == null || parameters == null || parameterTypeNames.length != parameters.length) {
            throw new RequestValidationException("Request parameter metadata does not match parameter values");
        }
        if (parameterTypeNames.length > CourierConstants.MAX_REQUEST_PARAMETERS) {
            throw new RequestValidationException("Request parameter count exceeds "
                    + CourierConstants.MAX_REQUEST_PARAMETERS);
        }
        for (String parameterTypeName : parameterTypeNames) {
            requireBoundedText(parameterTypeName, "parameterTypeName");
        }
        Map<String, String> attachments = request.getAttachments();
        if (attachments != null) {
            if (attachments.size() > CourierConstants.MAX_REQUEST_ATTACHMENTS) {
                throw new RequestValidationException("Request attachment count exceeds "
                        + CourierConstants.MAX_REQUEST_ATTACHMENTS);
            }
            attachments.forEach((key, value) -> {
                requireBoundedText(key, "attachment key");
                requireBoundedText(value, "attachment value");
            });
        }
    }

    private static void requirePositiveRequestId(String requestId) {
        requireBoundedText(requestId, "requestId");
        try {
            if (Long.parseLong(requestId) <= 0) {
                throw new NumberFormatException("not positive");
            }
        } catch (NumberFormatException e) {
            throw new RequestValidationException("requestId must be a positive integer");
        }
    }

    private static void requireBoundedText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new RequestValidationException(field + " must not be blank");
        }
        if (value.length() > CourierConstants.MAX_REQUEST_METADATA_STRING_LENGTH) {
            throw new RequestValidationException(field + " exceeds "
                    + CourierConstants.MAX_REQUEST_METADATA_STRING_LENGTH + " characters");
        }
    }
}
