package yunqi.courier.traffic.loadbalancing.roundrobin;

import org.junit.jupiter.api.Test;
import yunqi.courier.common.service.ServiceEndpoint;
import yunqi.courier.common.service.ServiceMetadata;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RoundRobinTrafficLoadBalancerTest {

    @Test
    void shouldRotateHealthyProvidersAndSkipUnhealthyOnes() {
        RoundRobinTrafficLoadBalancer loadBalancer = new RoundRobinTrafficLoadBalancer();
        ServiceMetadata first = provider("first", true);
        ServiceMetadata second = provider("second", true);
        ServiceMetadata down = provider("down", false);
        List<ServiceMetadata> providers = List.of(first, down, second);

        assertEquals(first, loadBalancer.select(providers));
        assertEquals(second, loadBalancer.select(providers));
        assertEquals(first, loadBalancer.select(providers));
    }

    private static ServiceMetadata provider(String host, boolean healthy) {
        ServiceMetadata metadata = new ServiceMetadata();
        metadata.setServiceName("round-robin");
        metadata.setEndpoint(new ServiceEndpoint(host, 20880));
        metadata.setAttributes(java.util.Map.of("healthy", Boolean.toString(healthy)));
        return metadata;
    }
}
