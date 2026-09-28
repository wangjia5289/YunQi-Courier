package yunqi.courier.network.netty;

import org.junit.jupiter.api.Test;
import yunqi.courier.api.bootstrap.ServiceExportConfig;
import yunqi.courier.api.bootstrap.ServiceReferenceConfig;
import yunqi.courier.common.config.CourierConfig;
import yunqi.courier.kernel.YunqiCourierBootstrap;

import java.net.ServerSocket;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ExtendedPluginsE2eTest {

    @Test
    void shouldInvokeWithBinarySerializerPayloadTransformsAndAlternativeProxy() throws Exception {
        CourierConfig config = new CourierConfig();
        config.setPort(availablePort());
        config.setSerialization("cbor");
        config.setCompression("lz4");
        config.setEncryption("aes-gcm");
        config.setEncryptionKey(Base64.getEncoder().encodeToString(new byte[16]));
        config.setProxying("bytebuddy");
        config.setLoadBalancing("least-active");

        YunqiCourierBootstrap bootstrap = new YunqiCourierBootstrap(config);
        try {
            bootstrap.export(ServiceExportConfig.of(EchoService.class, value -> "extended:" + value));
            bootstrap.start();
            EchoService service = bootstrap.refer(ServiceReferenceConfig.of(EchoService.class));
            assertEquals("extended:中文-payload", service.echo("中文-payload"));
        } finally {
            bootstrap.close();
        }
    }

    private static int availablePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    public interface EchoService {
        String echo(String value);
    }
}
