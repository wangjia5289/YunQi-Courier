package yunqi.courier.network.netty;

import yunqi.courier.api.bootstrap.ServiceExportConfig;
import yunqi.courier.api.bootstrap.ServiceReferenceConfig;
import yunqi.courier.api.context.RequestContext;
import yunqi.courier.common.config.CourierConfig;
import yunqi.courier.kernel.YunqiCourierBootstrap;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.concurrent.CompletableFuture;

class NettyRpcE2eTest {

    @Test
    void shouldInvokeRemoteServiceByNetty() throws Exception {
        CourierConfig config = new CourierConfig();
        config.setPort(20991);
        YunqiCourierBootstrap bootStrap = new YunqiCourierBootstrap(config);
        try {
            bootStrap.export(ServiceExportConfig.of(EchoService.class, new EchoServiceImpl()));
            bootStrap.start();

            EchoService echoService = bootStrap.refer(ServiceReferenceConfig.of(EchoService.class).timeoutMillis(3000));

            assertEquals("yunqi-courier:ok", echoService.echo("ok"));
            assertEquals(3, echoService.add(1, 2));
            CompletableFuture<String> async = bootStrap.referAsync(EchoService.class)
                    .<String>invoke("echo", "async");
            assertEquals("yunqi-courier:async", async.get());

            RequestContext.putAttachment("request-id", "ctx-1");
            RequestContext.setTraceId("trace-1");
            try {
                assertEquals("ctx-1:trace-1", bootStrap.referAsync(EchoService.class)
                        .<String>invoke("context").get());
            } finally {
                RequestContext.clear();
            }

            var asyncReference = bootStrap.referAsync(EchoService.class);
            assertEquals("integer", asyncReference.<String>invoke(
                    EchoService.class.getMethod("overloaded", Integer.class), new Object[]{null}).get());
            assertEquals(5, bootStrap.metrics().successCount());
        } finally {
            bootStrap.stop();
        }
    }

    interface EchoService {

        String echo(String message);

        int add(int left, int right);

        String context();

        String overloaded(String value);

        String overloaded(Integer value);
    }

    static class EchoServiceImpl implements EchoService {

        @Override
        public String echo(String message) {
            return "yunqi-courier:" + message;
        }

        @Override
        public int add(int left, int right) {
            return left + right;
        }

        @Override
        public String context() {
            return RequestContext.getAttachment("request-id") + ":" + RequestContext.getTraceId();
        }

        @Override
        public String overloaded(String value) {
            return "string:" + value;
        }

        @Override
        public String overloaded(Integer value) {
            return "integer";
        }
    }
}
