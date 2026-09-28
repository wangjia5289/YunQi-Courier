package yunqi.courier.common.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ServiceEndpointTest {

    @Test
    void normalizesBracketedIpv6AndParsesIt() {
        ServiceEndpoint endpoint = new ServiceEndpoint("[::1]", 20880);

        assertEquals("[::1]:20880", endpoint.address());
        assertEquals(endpoint, ServiceEndpoint.parse("[::1]:20880"));
    }

    @Test
    void rejectsInvalidHostAndPort() {
        assertThrows(IllegalArgumentException.class, () -> new ServiceEndpoint("", 20880));
        assertThrows(IllegalArgumentException.class, () -> new ServiceEndpoint("host", 0));
        assertThrows(IllegalArgumentException.class, () -> ServiceEndpoint.parse("[]:80"));
        assertThrows(IllegalArgumentException.class, () -> ServiceEndpoint.parse("host]:80"));
    }
}
