package yunqi.courier.network.netty;

import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.handler.ssl.SslContext;
import org.junit.jupiter.api.Test;
import yunqi.courier.common.config.CourierConfig;
import yunqi.courier.network.netty.ssl.TlsContextFactory;

import javax.net.ssl.SSLEngine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TlsContextFactoryTest {

    @Test
    void shouldUseJdkDefaultTrustRootsForClientWhenNoTrustStoreIsConfigured() {
        CourierConfig config = new CourierConfig();
        config.setTlsEnabled(true);

        SslContext context = TlsContextFactory.createClientContext(config);
        assertTrue(context.isClient());
        SSLEngine engine = context.newEngine(UnpooledByteBufAllocator.DEFAULT, "example.com", 443);
        assertEquals("HTTPS", engine.getSSLParameters().getEndpointIdentificationAlgorithm());
    }

    @Test
    void shouldFailWithoutServerKeyStoreWithoutExposingPassword() {
        CourierConfig config = new CourierConfig();
        config.setTlsEnabled(true);
        config.setTlsKeyStorePassword("do-not-log-this");

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TlsContextFactory.createServerContext(config));
        assertFalse(failure.getMessage().contains("do-not-log-this"));
    }
}
