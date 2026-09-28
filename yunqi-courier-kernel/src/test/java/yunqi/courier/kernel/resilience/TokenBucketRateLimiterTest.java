package yunqi.courier.kernel.resilience;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenBucketRateLimiterTest {

    @Test
    void shouldRejectWhenBucketIsEmpty() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(2);

        assertTrue(limiter.tryAcquire());
        assertTrue(limiter.tryAcquire());
        assertFalse(limiter.tryAcquire());
    }

    @Test
    void zeroLimitShouldDisableLimiting() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(0);

        assertTrue(limiter.tryAcquire());
        assertTrue(limiter.tryAcquire());
    }
}
