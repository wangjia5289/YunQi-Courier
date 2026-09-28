package yunqi.courier.kernel.resilience;

/** A small, synchronized token bucket. A limit of zero disables limiting. */
public final class TokenBucketRateLimiter {

    private final long capacity;

    private final double refillTokensPerNano;

    private double tokens;

    private long lastRefillNanos;

    public TokenBucketRateLimiter(int permitsPerSecond) {
        if (permitsPerSecond < 0) {
            throw new IllegalArgumentException("permitsPerSecond must not be negative");
        }
        this.capacity = permitsPerSecond;
        this.refillTokensPerNano = permitsPerSecond / 1_000_000_000d;
        this.tokens = permitsPerSecond;
        this.lastRefillNanos = System.nanoTime();
    }

    public synchronized boolean tryAcquire() {
        if (capacity == 0) {
            return true;
        }
        long now = System.nanoTime();
        long elapsed = Math.max(0, now - lastRefillNanos);
        tokens = Math.min(capacity, tokens + elapsed * refillTokensPerNano);
        lastRefillNanos = now;
        if (tokens < 1d) {
            return false;
        }
        tokens -= 1d;
        return true;
    }
}
