package yunqi.courier.traffic.loadbalancing.leastactive;

import org.junit.jupiter.api.Test;
import yunqi.courier.common.service.ServiceEndpoint;
import yunqi.courier.common.service.ServiceMetadata;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LeastActiveTrafficLoadBalancerTest {

    @Test
    void shouldPreferProviderWithFewerActiveRequests() {
        LeastActiveTrafficLoadBalancer loadBalancer = new LeastActiveTrafficLoadBalancer();
        ServiceMetadata busy = provider("busy");
        ServiceMetadata idle = provider("idle");
        loadBalancer.onRequestStart(idle);
        loadBalancer.onRequestStart(idle);
        loadBalancer.onRequestStart(busy);

        assertEquals(busy, loadBalancer.select(List.of(busy, idle)));

        loadBalancer.onRequestEnd(idle);
        loadBalancer.onRequestEnd(idle);
        loadBalancer.onRequestEnd(busy);
        assertEquals(0, loadBalancer.activeCount(busy));
        assertEquals(0, loadBalancer.activeCount(idle));
    }

    private static ServiceMetadata provider(String host) {
        ServiceMetadata metadata = new ServiceMetadata();
        metadata.setServiceName("least-active");
        metadata.setEndpoint(new ServiceEndpoint(host, 20880));
        return metadata;
    }
}
