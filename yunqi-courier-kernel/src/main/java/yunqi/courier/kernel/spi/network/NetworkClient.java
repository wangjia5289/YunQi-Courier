package yunqi.courier.kernel.spi.network;

import yunqi.courier.common.protocol.CourierRequest;
import yunqi.courier.common.protocol.CourierResponse;
import yunqi.courier.common.config.CourierConfig;
import yunqi.courier.common.service.ServiceEndpoint;

import java.util.concurrent.CompletableFuture;

public interface NetworkClient {

    default void configure(CourierConfig config) {
    }

    CompletableFuture<CourierResponse> sendRequest(CourierRequest request, ServiceEndpoint endpoint, int timeoutMillis);

    /** Rebuilds transport credentials for new connections. Existing channels may be closed by the implementation. */
    default void reloadTlsContext() {
    }

    /** Performs a bounded transport-level health probe. */
    default CompletableFuture<Boolean> checkEndpoint(ServiceEndpoint endpoint, int timeoutMillis) {
        return CompletableFuture.completedFuture(Boolean.TRUE);
    }

    void close();
}
