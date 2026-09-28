package yunqi.courier.common.protocol;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CourierResponseTest {

    @Test
    void applicationTimeoutIsTerminalFailure() {
        CourierResponse response = CourierResponse.failure("1", new TimeoutException("provider timeout"));

        assertEquals(ResponseCode.FAILURE, response.responseCode());
        assertEquals(false, response.retryableTransportFailure());
    }
}
