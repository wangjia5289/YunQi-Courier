package yunqi.courier.kernel.spi.traffic.loadbalancing;

import yunqi.courier.common.service.ServiceMetadata;

import java.util.List;

public interface TrafficLoadBalancer {

    ServiceMetadata select(List<ServiceMetadata> providers);
}
