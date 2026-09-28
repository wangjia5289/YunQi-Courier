package yunqi.courier.kernel.core;

import yunqi.courier.common.protocol.CourierRequest;
import yunqi.courier.common.protocol.CourierResponse;
import yunqi.courier.kernel.registry.ServiceRepository;
import yunqi.courier.kernel.spi.network.RequestProcessor;

public class DefaultRequestProcessor implements RequestProcessor {

    private final ServiceRepository serviceRepository;

    public DefaultRequestProcessor(ServiceRepository serviceRepository) {
        this.serviceRepository = serviceRepository;
    }

    @Override
    public CourierResponse process(CourierRequest request) {
        if (request == null) {
            return CourierResponse.failure(null, new IllegalArgumentException("request must not be null"));
        }
        try {
            Object result = serviceRepository.invoke(request);
            return CourierResponse.success(request.getRequestId(), result);
        } catch (Exception exception) {
            return CourierResponse.failure(request.getRequestId(), exception);
        }
    }
}
