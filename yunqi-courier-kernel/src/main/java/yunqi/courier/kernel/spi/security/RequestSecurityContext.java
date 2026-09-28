package yunqi.courier.kernel.spi.security;

import java.net.SocketAddress;
import javax.net.ssl.SSLSession;

/** Transport identity presented to an application access controller. */
public record RequestSecurityContext(SocketAddress remoteAddress, SSLSession sslSession) {
}
