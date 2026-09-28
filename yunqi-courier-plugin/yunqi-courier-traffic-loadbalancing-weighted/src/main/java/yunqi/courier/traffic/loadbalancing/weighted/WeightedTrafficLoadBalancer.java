package yunqi.courier.traffic.loadbalancing.weighted;

import yunqi.courier.common.exception.CourierException;
import yunqi.courier.common.constant.CourierConstants;
import yunqi.courier.common.service.ServiceMetadata;
import yunqi.courier.kernel.spi.core.SPI;
import yunqi.courier.kernel.spi.traffic.loadbalancing.TrafficLoadBalancer;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Smooth weighted round-robin using registry-provided metadata attributes.
 * The optional {@code healthy} attribute accepts {@code false}, {@code down}
 * or {@code unhealthy} as a negative health signal; missing values are UP.
 */
@SPI("weighted")
public class WeightedTrafficLoadBalancer implements TrafficLoadBalancer {

    private static final int DEFAULT_WEIGHT = 1;

    private static final int MAX_WEIGHT = 1000;

    private final Map<String, NodeState> states = new ConcurrentHashMap<>();

    @Override
    public synchronized ServiceMetadata select(List<ServiceMetadata> providers) {
        if (providers == null || providers.isEmpty()) {
            throw new CourierException("Provider list is empty");
        }
        List<Node> candidates = providers.stream()
                .filter(this::isHealthy)
                .map(provider -> new Node(provider, weight(provider)))
                .toList();
        if (candidates.isEmpty()) {
            throw new CourierException("No healthy provider available");
        }

        Set<String> activeKeys = new HashSet<>();
        long totalWeight = 0;
        Node selected = null;
        long selectedCurrent = Long.MIN_VALUE;
        for (Node candidate : candidates) {
            String key = nodeKey(candidate.metadata());
            activeKeys.add(key);
            NodeState state = states.computeIfAbsent(key, ignored -> new NodeState());
            state.current += candidate.weight();
            totalWeight += candidate.weight();
            if (selected == null || state.current > selectedCurrent) {
                selected = candidate;
                selectedCurrent = state.current;
            }
        }
        NodeState selectedState = states.get(nodeKey(selected.metadata()));
        selectedState.current -= totalWeight;
        states.keySet().retainAll(activeKeys);
        return selected.metadata();
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

    private int weight(ServiceMetadata provider) {
        String value = provider.getAttributes().get(CourierConstants.SERVICE_ATTRIBUTE_WEIGHT);
        if (value == null || value.isBlank()) {
            return DEFAULT_WEIGHT;
        }
        try {
            int parsed = Integer.parseInt(value);
            return parsed > 0 ? Math.min(parsed, MAX_WEIGHT) : DEFAULT_WEIGHT;
        } catch (NumberFormatException ignored) {
            return DEFAULT_WEIGHT;
        }
    }

    private String nodeKey(ServiceMetadata provider) {
        return provider.serviceKey() + "@" + provider.getEndpoint().address();
    }

    private record Node(ServiceMetadata metadata, int weight) {
    }

    private static final class NodeState {
        private long current;
    }
}
