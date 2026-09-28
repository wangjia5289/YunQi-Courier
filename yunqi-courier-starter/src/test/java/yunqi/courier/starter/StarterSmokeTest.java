package yunqi.courier.starter;

import org.junit.jupiter.api.Test;
import yunqi.courier.common.config.CourierConfig;
import yunqi.courier.kernel.YunqiCourierBootstrap;

import java.net.ServerSocket;

import static org.junit.jupiter.api.Assertions.assertTrue;

class StarterSmokeTest {

    @Test
    void shouldLoadDefaultPluginsFromStarterClasspath() {
        CourierConfig config = new CourierConfig();
        config.setPort(availablePort());
        YunqiCourierBootstrap courier = new YunqiCourierBootstrap(config);
        try {
            courier.start();
            assertTrue(courier.isStarted());
        } finally {
            courier.close();
        }
    }

    private static int availablePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (Exception e) {
            throw new AssertionError("Could not allocate an ephemeral port", e);
        }
    }
}
