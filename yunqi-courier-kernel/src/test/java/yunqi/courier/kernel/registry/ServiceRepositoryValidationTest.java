package yunqi.courier.kernel.registry;

import org.junit.jupiter.api.Test;
import yunqi.courier.common.protocol.CourierRequest;
import yunqi.courier.common.service.ServiceEndpoint;
import yunqi.courier.common.service.ServiceMetadata;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;

class ServiceRepositoryValidationTest {

    @Test
    void shouldRejectExcessiveParameterMetadataBeforeReflection() {
        ServiceRepository repository = repository();
        CourierRequest request = request();
        String[] parameterTypes = new String[257];
        Object[] parameters = new Object[257];
        java.util.Arrays.fill(parameterTypes, String.class.getName());
        request.setParameterTypeNames(parameterTypes);
        request.setParameters(parameters);

        assertThrows(RuntimeException.class, () -> repository.invoke(request));
    }

    @Test
    void shouldRejectExcessiveAttachmentMetadata() {
        ServiceRepository repository = repository();
        CourierRequest request = request();
        Map<String, String> attachments = new HashMap<>();
        for (int i = 0; i < 65; i++) {
            attachments.put("key-" + i, "value");
        }
        request.setAttachments(attachments);

        assertThrows(RuntimeException.class, () -> repository.invoke(request));
    }

    @Test
    void shouldRejectOversizedAttachmentValue() {
        ServiceRepository repository = repository();
        CourierRequest request = request();
        request.setAttachments(Map.of("key", "x".repeat(4_097)));

        assertThrows(RuntimeException.class, () -> repository.invoke(request));
    }

    @Test
    void shouldNotExposeStaticInterfaceMethods() {
        ServiceRepository repository = repository();
        CourierRequest request = request();
        request.setMethodName("staticHelper");
        request.setParameterTypeNames(new String[0]);
        request.setParameters(new Object[0]);

        assertThrows(RuntimeException.class, () -> repository.invoke(request));
    }

    @Test
    void shouldDispatchGenericInterfaceThroughErasedSignature() {
        ServiceRepository repository = new ServiceRepository();
        ServiceMetadata metadata = new ServiceMetadata();
        metadata.setServiceName(GenericService.class.getName());
        metadata.setEndpoint(new ServiceEndpoint("127.0.0.1", 20880));
        metadata.setServiceInterface(GenericService.class);
        repository.export(metadata, new GenericServiceImpl());

        CourierRequest request = new CourierRequest();
        request.setRequestId("2");
        request.setServiceName(metadata.serviceKey());
        request.setMethodName("echo");
        request.setParameterTypeNames(new String[]{Object.class.getName()});
        request.setParameters(new Object[]{"generic"});

        org.junit.jupiter.api.Assertions.assertEquals("generic", repository.invoke(request));
    }

    private static ServiceRepository repository() {
        ServiceRepository repository = new ServiceRepository();
        ServiceMetadata metadata = new ServiceMetadata();
        metadata.setServiceName(EchoService.class.getName() + ":default:1.0.0");
        metadata.setGroup("default");
        metadata.setVersion("1.0.0");
        metadata.setEndpoint(new ServiceEndpoint("127.0.0.1", 20880));
        metadata.setServiceInterface(EchoService.class);
        repository.export(metadata, new EchoServiceImpl());
        return repository;
    }

    private static CourierRequest request() {
        CourierRequest request = new CourierRequest();
        request.setRequestId("1");
        request.setServiceName(EchoService.class.getName() + ":default:1.0.0");
        request.setMethodName("echo");
        request.setParameterTypeNames(new String[]{String.class.getName()});
        request.setParameters(new Object[]{"ok"});
        return request;
    }

    interface EchoService {
        String echo(String value);

        static String staticHelper() {
            return "static";
        }
    }

    static final class EchoServiceImpl implements EchoService {
        @Override
        public String echo(String value) {
            return value;
        }
    }

    interface GenericService<T> {
        T echo(T value);
    }

    static final class GenericServiceImpl implements GenericService<String> {
        @Override
        public String echo(String value) {
            return value;
        }
    }
}
