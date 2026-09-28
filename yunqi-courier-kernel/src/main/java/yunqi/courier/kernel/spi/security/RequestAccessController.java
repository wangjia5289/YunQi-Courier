package yunqi.courier.kernel.spi.security;

import yunqi.courier.common.protocol.CourierRequest;

import java.net.SocketAddress;

/** Authentication and service/method authorization boundary for inbound calls. */
public interface RequestAccessController {

    RequestAccessController ALLOW_ALL = new RequestAccessController() {
        @Override
        public Object authenticate(CourierRequest request, SocketAddress remoteAddress) {
            return null;
        }

        @Override
        public void authorize(Object principal, CourierRequest request) {
        }
    };

    Object authenticate(CourierRequest request, SocketAddress remoteAddress);

    default Object authenticateWithContext(CourierRequest request, RequestSecurityContext securityContext) {
        return authenticate(request, securityContext == null ? null : securityContext.remoteAddress());
    }

    void authorize(Object principal, CourierRequest request);

    default Object checkAddress(CourierRequest request, SocketAddress remoteAddress) {
        Object principal = authenticate(request, remoteAddress);
        authorize(principal, request);
        return principal;
    }

    default Object check(CourierRequest request, RequestSecurityContext securityContext) {
        Object principal = authenticateWithContext(request, securityContext);
        authorize(principal, request);
        return principal;
    }
}
