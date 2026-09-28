package yunqi.courier.kernel.spi.network;

import yunqi.courier.common.protocol.CourierRequest;
import yunqi.courier.common.protocol.CourierResponse;

public interface RequestProcessor {

    CourierResponse process(CourierRequest request);
}
