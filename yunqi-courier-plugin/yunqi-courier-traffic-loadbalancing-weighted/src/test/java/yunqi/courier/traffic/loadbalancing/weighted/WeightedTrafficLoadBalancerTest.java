package yunqi.courier.traffic.loadbalancing.weighted;

import org.junit.jupiter.api.Test;
import yunqi.courier.common.service.ServiceEndpoint;
import yunqi.courier.common.service.ServiceMetadata;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WeightedTrafficLoadBalancerTest {

    @Test
    void shouldDistributeBySmoothWeight() {
        WeightedTrafficLoadBalancer loadBalancer = new WeightedTrafficLoadBalancer();
        ServiceMetadata two = provider("two", 2, true);
        ServiceMetadata one = provider("one", 1, true);
        List<ServiceMetadata> providers = List.of(two, one);

        int twoCount = 0;
        int oneCount = 0;
        for (int i = 0; i < 6; i++) {
            if (loadBalancer.select(providers) == two) {
                twoCount++;
            } else {
                oneCount++;
            }
        }
        assertEquals(4, twoCount);
        assertEquals(2, oneCount);
    }

    @Test
    void shouldIgnoreUnhealthyProvidersAndNotMutateInput() {
        WeightedTrafficLoadBalancer loadBalancer = new WeightedTrafficLoadBalancer();
        ServiceMetadata unhealthy = provider("down", 100, false);
        ServiceMetadata healthy = provider("up", 1, true);
        List<ServiceMetadata> providers = new ArrayList<>(List.of(unhealthy, healthy));

        assertEquals(healthy, loadBalancer.select(providers));
        assertEquals(List.of(unhealthy, healthy), providers);
    }

    @Test
    void shouldFailClosedWhenAllProvidersAreUnhealthy() {
        WeightedTrafficLoadBalancer loadBalancer = new WeightedTrafficLoadBalancer();
        assertThrows(RuntimeException.class,
                () -> loadBalancer.select(List.of(provider("down", 1, false))));
    }

    @Test
    void shouldFailClosedWhenEitherHealthMarkerIsNegative() {
        WeightedTrafficLoadBalancer loadBalancer = new WeightedTrafficLoadBalancer();
        ServiceMetadata contradictory = provider("contradictory", 1, true);
        contradictory.setAttributes(java.util.Map.of("healthy", "true", "health", "down"));
        assertThrows(RuntimeException.class, () -> loadBalancer.select(List.of(contradictory)));
    }

    private static ServiceMetadata provider(String host, int weight, boolean healthy) {
        ServiceMetadata metadata = new ServiceMetadata();
        metadata.setServiceName("weighted-service");
        metadata.setEndpoint(new ServiceEndpoint(host, 20880));
        metadata.setAttributes(java.util.Map.of("weight", Integer.toString(weight),
                "healthy", Boolean.toString(healthy)));
        return metadata;
    }
}
