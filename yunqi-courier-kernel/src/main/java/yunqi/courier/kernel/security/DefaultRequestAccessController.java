package yunqi.courier.kernel.security;

import yunqi.courier.common.config.CourierConfig;
import yunqi.courier.common.exception.AuthenticationException;
import yunqi.courier.common.exception.AuthorizationException;
import yunqi.courier.common.protocol.CourierRequest;
import yunqi.courier.kernel.spi.security.RequestAccessController;

import java.net.SocketAddress;
import java.util.Arrays;
import java.util.Map;

/** Constant-time token authentication plus optional service/method allow-list. */
public final class DefaultRequestAccessController implements RequestAccessController {

    private final String expectedToken;
    private final boolean required;
    private final Map<String, String> rules;

    public DefaultRequestAccessController(CourierConfig config) {
        this.expectedToken = config.getAuthToken();
        this.required = config.isRequireAuthentication();
        this.rules = config.getAuthorizationRules();
    }

    @Override
    public Object authenticate(CourierRequest request, SocketAddress remoteAddress) {
        String supplied = request == null ? null : request.getAuthenticationToken();
        if (expectedToken == null || expectedToken.isBlank()) {
            if (required) {
                throw new AuthenticationException("Authentication is enabled but no server token is configured");
            }
            return supplied;
        }
        if (supplied == null || !java.security.MessageDigest.isEqual(
                expectedToken.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                supplied.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            throw new AuthenticationException("Invalid authentication token");
        }
        return supplied;
    }

    @Override
    public void authorize(Object principal, CourierRequest request) {
        if (rules.isEmpty()) {
            return;
        }
        String allowed = rules.get(request.getServiceName());
        if (allowed == null) {
            throw new AuthorizationException("Service is not authorized, service=" + request.getServiceName());
        }
        if (!allowed.isBlank()) {
            boolean methodAllowed = Arrays.stream(allowed.split(","))
                    .map(String::trim)
                    .anyMatch(request.getMethodName()::equals);
            if (!methodAllowed) {
                throw new AuthorizationException("Method is not authorized, service=" + request.getServiceName()
                        + ", method=" + request.getMethodName());
            }
        }
    }
}
