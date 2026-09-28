package yunqi.courier.kernel.spi.traffic.loadbalancing;

import yunqi.courier.common.service.ServiceMetadata;

/** Optional lifecycle callbacks used by load balancers that track in-flight work. */
public interface ActiveRequestTracker {

    void onRequestStart(ServiceMetadata provider);

    void onRequestEnd(ServiceMetadata provider);
}
