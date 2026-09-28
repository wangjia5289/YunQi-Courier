package yunqi.courier.kernel.security;

import org.junit.jupiter.api.Test;
import yunqi.courier.common.config.CourierConfig;
import yunqi.courier.common.exception.AuthenticationException;
import yunqi.courier.common.exception.AuthorizationException;
import yunqi.courier.common.protocol.CourierRequest;

import static org.junit.jupiter.api.Assertions.assertThrows;

class DefaultRequestAccessControllerTest {

    @Test
    void rejectsWrongTokenAndUnauthorizedMethod() {
        CourierConfig config = new CourierConfig();
        config.setTlsEnabled(true);
        config.setAuthToken("secret");
        config.authorizationRule("demo:default:1.0.0", "read");
        DefaultRequestAccessController controller = new DefaultRequestAccessController(config);
        CourierRequest request = new CourierRequest();
        request.setRequestId("1");
        request.setServiceName("demo:default:1.0.0");
        request.setMethodName("write");
        request.setParameterTypeNames(new String[0]);
        request.setParameters(new Object[0]);
        request.setAuthenticationToken("wrong");
        assertThrows(AuthenticationException.class, () -> controller.checkAddress(request, null));
        request.setAuthenticationToken("secret");
        assertThrows(AuthorizationException.class, () -> controller.checkAddress(request, null));
    }
}
