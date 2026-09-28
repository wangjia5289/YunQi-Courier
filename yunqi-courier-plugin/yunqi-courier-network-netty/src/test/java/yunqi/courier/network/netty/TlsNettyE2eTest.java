package yunqi.courier.network.netty;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import yunqi.courier.api.bootstrap.ServiceExportConfig;
import yunqi.courier.api.bootstrap.ServiceReferenceConfig;
import yunqi.courier.common.config.CourierConfig;
import yunqi.courier.kernel.YunqiCourierBootstrap;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TlsNettyE2eTest {

    @Test
    void shouldInvokeServiceOverMutualTls(@TempDir Path tempDir) throws Exception {
        String password = "changeit";
        Path keyStore = tempDir.resolve("identity.p12");
        Path certificate = tempDir.resolve("identity.crt");
        Path trustStore = tempDir.resolve("trust.p12");
        generateIdentity(keyStore, certificate, trustStore, password);

        CourierConfig config = new CourierConfig();
        config.setPort(availablePort());
        config.setTlsEnabled(true);
        config.setTlsMutualAuth(true);
        config.setTlsKeyStorePath(keyStore.toString());
        config.setTlsKeyStorePassword(password);
        config.setTlsTrustStorePath(trustStore.toString());
        config.setTlsTrustStorePassword(password);
        config.setConnectTimeoutMillis(2000);

        YunqiCourierBootstrap courier = new YunqiCourierBootstrap(config);
        try {
            courier.export(ServiceExportConfig.of(EchoService.class, new EchoServiceImpl()));
            courier.start();

            EchoService echo = courier.refer(ServiceReferenceConfig.of(EchoService.class)
                    .timeoutMillis(5000));
            assertEquals("secure:ok", echo.echo("ok"));
        } finally {
            courier.close();
        }
    }

    private static void generateIdentity(Path keyStore, Path certificate, Path trustStore,
                                         String password) throws Exception {
        String keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
        if (!Files.isExecutable(Path.of(keytool))) {
            keytool = Path.of(System.getProperty("java.home"), "..", "bin", "keytool")
                    .toAbsolutePath().normalize().toString();
        }
        run(keytool, "-genkeypair", "-alias", "courier", "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "2", "-storetype", "PKCS12", "-keystore", keyStore.toString(),
                "-storepass", password, "-keypass", password, "-dname", "CN=127.0.0.1",
                "-ext", "SAN=IP:127.0.0.1", "-noprompt");
        run(keytool, "-exportcert", "-alias", "courier", "-keystore", keyStore.toString(),
                "-storepass", password, "-rfc", "-file", certificate.toString());
        run(keytool, "-importcert", "-alias", "courier", "-file", certificate.toString(),
                "-keystore", trustStore.toString(), "-storetype", "PKCS12", "-storepass",
                password, "-noprompt");
    }

    private static void run(String command, String... arguments) throws Exception {
        List<String> commandLine = new ArrayList<>();
        commandLine.add(command);
        commandLine.addAll(List.of(arguments));
        Process process = new ProcessBuilder(commandLine).redirectErrorStream(true).start();
        String output;
        try (var input = process.getInputStream()) {
            output = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        if (process.waitFor() != 0) {
            throw new IOException("keytool failed: " + output);
        }
    }

    private static int availablePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    interface EchoService {
        String echo(String message);
    }

    static class EchoServiceImpl implements EchoService {
        @Override
        public String echo(String message) {
            return "secure:" + message;
        }
    }
}
