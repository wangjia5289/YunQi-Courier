package yunqi.courier.common.protocol;

import java.io.Serializable;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class CourierRequest implements Serializable {

    private String requestId;

    /** Optional credential propagated by the configured authentication policy. */
    private String authenticationToken;

    private String traceId;

    private String serviceName;

    private String methodName;

    private String[] parameterTypeNames;

    private Object[] parameters;

    private Map<String, String> attachments = new HashMap<>();

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getAuthenticationToken() {
        return authenticationToken;
    }

    public void setAuthenticationToken(String authenticationToken) {
        this.authenticationToken = authenticationToken;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public String getMethodName() {
        return methodName;
    }

    public void setMethodName(String methodName) {
        this.methodName = methodName;
    }

    public String[] getParameterTypeNames() {
        return parameterTypeNames == null ? null : parameterTypeNames.clone();
    }

    public void setParameterTypeNames(String[] parameterTypeNames) {
        this.parameterTypeNames = parameterTypeNames == null ? null : parameterTypeNames.clone();
    }

    public Object[] getParameters() {
        return parameters == null ? null : parameters.clone();
    }

    public void setParameters(Object[] parameters) {
        this.parameters = parameters == null ? null : parameters.clone();
    }

    public Map<String, String> getAttachments() {
        return attachments == null
                ? null
                : Collections.unmodifiableMap(new HashMap<>(attachments));
    }

    public void setAttachments(Map<String, String> attachments) {
        this.attachments = attachments == null ? null : new HashMap<>(attachments);
    }

    @Override
    public String toString() {
        return "CourierRequest{" +
                "requestId='" + requestId + '\'' +
                ", serviceName='" + serviceName + '\'' +
                ", methodName='" + methodName + '\'' +
                ", parameterTypeNames=" + Arrays.toString(parameterTypeNames) +
                '}';
    }
}
