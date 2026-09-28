package yunqi.courier.traffic.loadbalancing.leastactive;

import yunqi.courier.common.exception.CourierException;
import yunqi.courier.common.constant.CourierConstants;
import yunqi.courier.common.service.ServiceMetadata;
import yunqi.courier.kernel.spi.core.SPI;
import yunqi.courier.kernel.spi.traffic.loadbalancing.ActiveRequestTracker;
import yunqi.courier.kernel.spi.traffic.loadbalancing.TrafficLoadBalancer;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/** Selects among healthy providers with the fewest in-flight requests. */
@SPI("least-active")
public final class LeastActiveTrafficLoadBalancer implements TrafficLoadBalancer, ActiveRequestTracker {

    private final ConcurrentHashMap<String, AtomicInteger> active = new ConcurrentHashMap<>();

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
        int minimum = Integer.MAX_VALUE;
        int count = 0;
        for (ServiceMetadata provider : healthy) {
            int countForProvider = count(provider);
            if (countForProvider < minimum) {
                minimum = countForProvider;
                count = 1;
            } else if (countForProvider == minimum) {
                count++;
            }
        }
        int selectedIndex = ThreadLocalRandom.current().nextInt(count);
        for (ServiceMetadata provider : healthy) {
            if (count(provider) == minimum && selectedIndex-- == 0) {
                return provider;
            }
        }
        throw new IllegalStateException("Unable to select least-active provider");
    }

    @Override
    public void onRequestStart(ServiceMetadata provider) {
        if (provider != null && provider.getEndpoint() != null) {
            active.computeIfAbsent(key(provider), ignored -> new AtomicInteger()).incrementAndGet();
        }
    }

    @Override
    public void onRequestEnd(ServiceMetadata provider) {
        if (provider != null && provider.getEndpoint() != null) {
            active.computeIfAbsent(key(provider), ignored -> new AtomicInteger())
                    .updateAndGet(value -> Math.max(0, value - 1));
        }
    }

    int activeCount(ServiceMetadata provider) {
        return count(provider);
    }

    private int count(ServiceMetadata provider) {
        AtomicInteger value = active.get(key(provider));
        return value == null ? 0 : value.get();
    }

    private String key(ServiceMetadata provider) {
        return provider.serviceKey() + "@" + provider.getEndpoint().address();
    }

    private boolean isHealthy(ServiceMetadata provider) {
        String healthy = provider.getAttributes().get(CourierConstants.SERVICE_ATTRIBUTE_HEALTHY);
        String health = provider.getAttributes().get(CourierConstants.SERVICE_ATTRIBUTE_HEALTH);
        return !isNegative(healthy) && !isNegative(health);
    }

    private boolean isNegative(String value) {
        return value != null && (value.equalsIgnoreCase("false")
                || value.equalsIgnoreCase("down") || value.equalsIgnoreCase("unhealthy"));
    }
}
