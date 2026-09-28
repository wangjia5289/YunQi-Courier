package yunqi.courier.common.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CourierConfigTest {

    @Test
    void shouldCopyTlsSettingsDefensively() {
        CourierConfig config = new CourierConfig();
        config.setTlsEnabled(true);
        config.setTlsKeyStorePath("server.p12");
        config.setTlsProtocols("TLSv1.3", "TLSv1.2");

        CourierConfig copy = config.copy();
        String[] protocols = copy.getTlsProtocols();
        protocols[0] = "TLSv1.1";

        assertArrayEquals(new String[]{"TLSv1.3", "TLSv1.2"}, config.getTlsProtocols());
        assertArrayEquals(new String[]{"TLSv1.3", "TLSv1.2"}, copy.getTlsProtocols());
    }

    @Test
    void shouldRejectMutualAuthWithoutTls() {
        CourierConfig config = new CourierConfig();
        config.setTlsMutualAuth(true);

        assertThrows(IllegalArgumentException.class, config::validate);
    }

    @Test
    void shouldRequireExplicitTrustStoreForMutualAuth() {
        CourierConfig config = new CourierConfig();
        config.setTlsEnabled(true);
        config.setTlsMutualAuth(true);

        assertThrows(IllegalArgumentException.class, config::validate);
    }

    @Test
    void shouldRejectEmptyTlsProtocolList() {
        CourierConfig config = new CourierConfig();
        config.setTlsProtocols();

        assertThrows(IllegalArgumentException.class, config::validate);
    }

    @Test
    void shouldRejectBearerAuthenticationOverPlaintext() {
        CourierConfig config = new CourierConfig();
        config.setAuthToken("secret");
        assertThrows(IllegalArgumentException.class, config::validate);
    }

    @Test
    void shouldValidateAdvertisedEndpointAndCopyIt() {
        CourierConfig config = new CourierConfig();
        config.setAdvertisedHost("rpc.example.internal");
        config.setAdvertisedPort(21880);
        config.validate();

        CourierConfig copy = config.copy();
        assertEquals("rpc.example.internal", copy.getAdvertisedHost());
        assertEquals(21880, copy.getAdvertisedPort());
    }

    @Test
    void shouldRejectInvalidAdvertisedPort() {
        CourierConfig config = new CourierConfig();
        config.setAdvertisedPort(0);

        assertThrows(IllegalArgumentException.class, config::validate);
    }

    @Test
    void shouldCopyDistributedRegistrySettings() {
        CourierConfig config = new CourierConfig();
        config.setRegistryAddress("zk-1:2181,zk-2:2181");
        config.setRegistryNamespace("production");
        config.setRegistryConnectTimeoutMillis(3_000);
        config.setRegistrySessionTimeoutMillis(9_000);
        config.setRegistryRetryBaseSleepMillis(250);
        config.setRegistryRetryMaxRetries(7);
        config.setRegistrySecurityEnabled(true);
        config.setRegistrySecurityUsername("courier");
        config.setRegistrySecurityPassword("secret");
        config.setRegistryTlsEnabled(true);
        config.setRegistryTlsTrustStorePath("trust.p12");
        config.setRegistryTlsTrustStorePassword("changeit");
        config.setRegistryTlsHostnameVerificationEnabled(false);

        CourierConfig copy = config.copy();

        assertEquals("zk-1:2181,zk-2:2181", copy.getRegistryAddress());
        assertEquals("production", copy.getRegistryNamespace());
        assertEquals(3_000, copy.getRegistryConnectTimeoutMillis());
        assertEquals(9_000, copy.getRegistrySessionTimeoutMillis());
        assertEquals(250, copy.getRegistryRetryBaseSleepMillis());
        assertEquals(7, copy.getRegistryRetryMaxRetries());
        assertEquals("courier", copy.getRegistrySecurityUsername());
        assertEquals("secret", copy.getRegistrySecurityPassword());
        assertEquals("trust.p12", copy.getRegistryTlsTrustStorePath());
        assertEquals("changeit", copy.getRegistryTlsTrustStorePassword());
        assertEquals(false, copy.isRegistryTlsHostnameVerificationEnabled());
    }

    @Test
    void shouldRejectInvalidDistributedRegistrySettings() {
        CourierConfig config = new CourierConfig();
        config.setRegistryNamespace("bad//namespace");
        assertThrows(IllegalArgumentException.class, config::validate);

        config = new CourierConfig();
        config.setRegistryRetryMaxRetries(101);
        assertThrows(IllegalArgumentException.class, config::validate);

        config = new CourierConfig();
        config.setRegistrySecurityEnabled(true);
        config.setRegistrySecurityUsername("courier");
        config.setRegistrySecurityPassword("secret");
        assertThrows(IllegalArgumentException.class, config::validate);

        config = new CourierConfig();
        config.setRegistryTlsEnabled(true);
        assertThrows(IllegalArgumentException.class, config::validate);
    }

    @Test
    void shouldValidateAndCopyMaxConnections() {
        CourierConfig config = new CourierConfig();
        config.setMaxConnections(12);
        config.setClientAutoReconnect(false);
        config.setClientReconnectBaseDelayMillis(25);
        config.setClientReconnectMaxDelayMillis(250);
        config.validate();

        assertEquals(12, config.copy().getMaxConnections());
        assertEquals(false, config.copy().isClientAutoReconnect());
        assertEquals(25, config.copy().getClientReconnectBaseDelayMillis());
        assertEquals(250, config.copy().getClientReconnectMaxDelayMillis());

        config.setMaxConnections(0);
        assertThrows(IllegalArgumentException.class, config::validate);
    }

    @Test
    void shouldValidateAndCopyManagementEndpoint() {
        CourierConfig config = new CourierConfig();
        config.setManagementEnabled(true);
        config.setManagementHost("0.0.0.0");
        config.setManagementPort(18081);
        config.validate();

        CourierConfig copy = config.copy();
        assertEquals(true, copy.isManagementEnabled());
        assertEquals("0.0.0.0", copy.getManagementHost());
        assertEquals(18081, copy.getManagementPort());

        config.setManagementPort(65_536);
        assertThrows(IllegalArgumentException.class, config::validate);
    }

    @Test
    void shouldValidateAndCopyPayloadTransforms() {
        CourierConfig config = new CourierConfig();
        config.setCompression("lz4");
        config.setEncryption("aes-gcm");
        config.setEncryptionKey("AAECAwQFBgcICQoLDA0ODw==");
        config.validate();

        CourierConfig copy = config.copy();
        assertEquals("lz4", copy.getCompression());
        assertEquals("aes-gcm", copy.getEncryption());
        assertEquals("AAECAwQFBgcICQoLDA0ODw==", copy.getEncryptionKey());

        config.setEncryptionKey(null);
        assertThrows(IllegalArgumentException.class, config::validate);
    }
}
