package yunqi.courier.kernel.spi.registry;

import yunqi.courier.common.service.ServiceMetadata;

import java.util.List;

@FunctionalInterface
public interface RegistryListener {
    void onChange(String serviceKey, List<ServiceMetadata> providers);
}
