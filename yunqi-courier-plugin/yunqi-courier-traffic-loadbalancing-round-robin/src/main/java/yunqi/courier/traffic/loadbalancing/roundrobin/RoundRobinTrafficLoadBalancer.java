package yunqi.courier.traffic.loadbalancing.roundrobin;

import yunqi.courier.common.constant.CourierConstants;
import yunqi.courier.common.exception.CourierException;
import yunqi.courier.common.service.ServiceMetadata;
import yunqi.courier.kernel.spi.core.SPI;
import yunqi.courier.kernel.spi.traffic.loadbalancing.TrafficLoadBalancer;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Deterministic round-robin over currently healthy providers. */
@SPI("round-robin")
public final class RoundRobinTrafficLoadBalancer implements TrafficLoadBalancer {

    private final AtomicInteger cursor = new AtomicInteger();

    @Override
    public ServiceMetadata select(List<ServiceMetadata> providers) {
        if (providers == null || providers.isEmpty()) {
            throw new CourierException("Provider list is empty");
        }
        List<ServiceMetadata> healthy = providers.stream()
                .filter(provider -> provider != null && provider.getEndpoint() != null
                        && provider.getEndpoint().isValid() && isHealthy(provider))
                .toList();
        if (healthy.isEmpty()) {
            throw new CourierException("No healthy provider available");
        }
        int index = Math.floorMod(cursor.getAndIncrement(), healthy.size());
        return healthy.get(index);
    }

    private boolean isHealthy(ServiceMetadata provider) {
        return !isNegative(provider.getAttributes().get(CourierConstants.SERVICE_ATTRIBUTE_HEALTHY))
                && !isNegative(provider.getAttributes().get(CourierConstants.SERVICE_ATTRIBUTE_HEALTH));
    }

    private boolean isNegative(String value) {
        return value != null && (value.equalsIgnoreCase("false")
                || value.equalsIgnoreCase("down") || value.equalsIgnoreCase("unhealthy"));
    }
}
