package yunqi.courier.traffic.loadbalancing.random;

import yunqi.courier.common.exception.CourierException;
import yunqi.courier.common.constant.CourierConstants;
import yunqi.courier.common.service.ServiceMetadata;
import yunqi.courier.kernel.spi.core.SPI;
import yunqi.courier.kernel.spi.traffic.loadbalancing.TrafficLoadBalancer;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

@SPI("random")
public class RandomTrafficLoadBalancer implements TrafficLoadBalancer {

    @Override
    public ServiceMetadata select(List<ServiceMetadata> providers) {
        if (providers == null || providers.isEmpty()) {
            throw new CourierException("Provider list is empty");
        }
        List<ServiceMetadata> healthy = providers.stream()
                .filter(this::isHealthy)
                .toList();
        if (healthy.isEmpty()) {
            throw new CourierException("No healthy provider available");
        }
        return healthy.get(ThreadLocalRandom.current().nextInt(healthy.size()));
    }

    private boolean isHealthy(ServiceMetadata provider) {
        if (provider == null || provider.getEndpoint() == null) {
            return false;
        }
        Map<String, String> attributes = provider.getAttributes();
        return !isNegativeHealth(attributes.get(CourierConstants.SERVICE_ATTRIBUTE_HEALTHY))
                && !isNegativeHealth(attributes.get(CourierConstants.SERVICE_ATTRIBUTE_HEALTH));
    }

    private boolean isNegativeHealth(String value) {
        return value != null && (value.equalsIgnoreCase("false")
                || value.equalsIgnoreCase("down")
                || value.equalsIgnoreCase("unhealthy"));
    }
}
