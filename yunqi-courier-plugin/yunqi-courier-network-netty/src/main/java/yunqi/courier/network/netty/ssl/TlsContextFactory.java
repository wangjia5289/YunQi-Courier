package yunqi.courier.network.netty.ssl;

import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import yunqi.courier.common.config.CourierConfig;
import yunqi.courier.common.exception.RemotingException;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Objects;

/** Builds JDK-backed Netty TLS contexts without permissive trust-all managers. */
public final class TlsContextFactory {

    private TlsContextFactory() {
    }

    public static SslContext createServerContext(CourierConfig config) {
        Objects.requireNonNull(config, "config");
        requireEnabled(config);
        try {
            KeyManagerFactory keyManagerFactory = keyManagerFactory(config);
            SslContextBuilder builder = SslContextBuilder.forServer(keyManagerFactory)
                    .protocols(config.getTlsProtocols())
                    .clientAuth(config.isTlsMutualAuth() ? ClientAuth.REQUIRE : ClientAuth.NONE);
            if (config.isTlsMutualAuth()) {
                builder.trustManager(trustManagerFactory(config));
            }
            return builder.build();
        } catch (GeneralSecurityException | IOException e) {
            throw failure("Build TLS server context", config.getTlsKeyStorePath(), e);
        }
    }

    public static SslContext createClientContext(CourierConfig config) {
        Objects.requireNonNull(config, "config");
        requireEnabled(config);
        try {
            SslContextBuilder builder = SslContextBuilder.forClient()
                    .protocols(config.getTlsProtocols());
            if (config.isTlsHostnameVerificationEnabled()) {
                builder.endpointIdentificationAlgorithm("HTTPS");
            }
            builder.trustManager(trustManagerFactory(config));
            if (config.isTlsMutualAuth()) {
                builder.keyManager(keyManagerFactory(config));
            }
            return builder.build();
        } catch (GeneralSecurityException | IOException e) {
            throw failure("Build TLS client context", config.getTlsTrustStorePath(), e);
        }
    }

    private static KeyManagerFactory keyManagerFactory(CourierConfig config)
            throws GeneralSecurityException, IOException {
        String path = config.getTlsKeyStorePath();
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("tlsKeyStorePath is required when TLS is enabled");
        }
        KeyStore keyStore = loadStore(path, config.getTlsKeyStoreType(), config.getTlsKeyStorePassword());
        KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        char[] password = secret(config.getTlsKeyStorePassword());
        try {
            factory.init(keyStore, password);
        } finally {
            clear(password);
        }
        return factory;
    }

    private static TrustManagerFactory trustManagerFactory(CourierConfig config)
            throws GeneralSecurityException, IOException {
        TrustManagerFactory factory = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        String path = config.getTlsTrustStorePath();
        if (path == null || path.isBlank()) {
            if (config.isTlsMutualAuth()) {
                throw new IllegalArgumentException("tlsTrustStorePath is required when tlsMutualAuth is enabled");
            }
            // A null KeyStore asks the JDK to use its configured default CA set.
            factory.init((KeyStore) null);
            return factory;
        }
        KeyStore trustStore = loadStore(path, config.getTlsTrustStoreType(), config.getTlsTrustStorePassword());
        factory.init(trustStore);
        return factory;
    }

    private static KeyStore loadStore(String path, String type, String password)
            throws GeneralSecurityException, IOException {
        KeyStore keyStore = KeyStore.getInstance(type);
        char[] secret = secret(password);
        try (InputStream input = Files.newInputStream(Path.of(path))) {
            keyStore.load(input, secret);
            return keyStore;
        } finally {
            clear(secret);
        }
    }

    private static char[] secret(String value) {
        return value == null ? null : value.toCharArray();
    }

    private static void clear(char[] value) {
        if (value != null) {
            java.util.Arrays.fill(value, '\0');
        }
    }

    private static void requireEnabled(CourierConfig config) {
        if (!config.isTlsEnabled()) {
            throw new IllegalArgumentException("TLS is not enabled");
        }
    }

    private static RemotingException failure(String action, String path, Exception cause) {
        String location = path == null || path.isBlank() ? "configured stores" : path;
        return new RemotingException(action + " failed, store=" + location, cause);
    }
}
