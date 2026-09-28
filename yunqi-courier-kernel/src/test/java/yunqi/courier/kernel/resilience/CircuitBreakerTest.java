package yunqi.courier.kernel.resilience;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CircuitBreakerTest {

    @Test
    void shouldOpenAndProbeAfterResetTimeout() throws Exception {
        CircuitBreaker breaker = new CircuitBreaker(2, 20);

        assertTrue(breaker.allowRequest());
        breaker.recordFailure();
        assertTrue(breaker.allowRequest());
        breaker.recordFailure();
        assertFalse(breaker.allowRequest());

        Thread.sleep(30);
        assertTrue(breaker.allowRequest());
        assertFalse(breaker.allowRequest());
        breaker.recordSuccess();
        assertTrue(breaker.allowRequest());
    }

    @Test
    void lateSuccessMustNotCloseAnOpenBreaker() {
        CircuitBreaker breaker = new CircuitBreaker(1, 1000);

        breaker.recordFailure();
        assertEquals(CircuitBreaker.State.OPEN, breaker.state());

        breaker.recordSuccess();

        assertEquals(CircuitBreaker.State.OPEN, breaker.state());
        assertFalse(breaker.allowRequest());
    }
}
