package yunqi.courier.kernel.spi.network;

import yunqi.courier.common.config.CourierConfig;
import yunqi.courier.kernel.spi.network.RequestProcessor;
import yunqi.courier.kernel.spi.security.RequestAccessController;

public interface NetworkServer {

    default void configure(CourierConfig config) {
    }

    void start(String host, int port, RequestProcessor requestProcessor);

    default void start(String host, int port, RequestProcessor requestProcessor, int maxFrameLength) {
        start(host, port, requestProcessor);
    }

    default void start(String host, int port, RequestProcessor requestProcessor, int maxFrameLength,
                       RequestAccessController accessController) {
        start(host, port, requestProcessor, maxFrameLength);
    }

    /** Rebuilds transport credentials for subsequently accepted connections. */
    default void reloadTlsContext() {
    }

    void stop();
}
