package yunqi.courier.kernel.resilience;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Thread-safe circuit breaker for transport failures. */
public final class CircuitBreaker {

    public enum State {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    private final int failureThreshold;

    private final long resetTimeoutNanos;

    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);

    private final AtomicInteger consecutiveFailures = new AtomicInteger();

    private volatile long openedAtNanos;

    public CircuitBreaker(int failureThreshold, int resetTimeoutMillis) {
        if (failureThreshold <= 0) {
            throw new IllegalArgumentException("failureThreshold must be positive");
        }
        if (resetTimeoutMillis <= 0) {
            throw new IllegalArgumentException("resetTimeoutMillis must be positive");
        }
        this.failureThreshold = failureThreshold;
        this.resetTimeoutNanos = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(resetTimeoutMillis);
    }

    public boolean allowRequest() {
        State current = state.get();
        if (current == State.CLOSED) {
            return true;
        }
        if (current == State.HALF_OPEN) {
            return false;
        }
        if (System.nanoTime() - openedAtNanos < resetTimeoutNanos) {
            return false;
        }
        return state.compareAndSet(State.OPEN, State.HALF_OPEN);
    }

    public void recordSuccess() {
        if (state.get() == State.OPEN) {
            // A request that started before the breaker opened must not close it
            // after a later transport failure has already established OPEN.
            return;
        }
        consecutiveFailures.set(0);
        state.compareAndSet(State.HALF_OPEN, State.CLOSED);
    }

    public void recordFailure() {
        State current = state.get();
        if (current == State.OPEN) {
            return;
        }
        if (current == State.HALF_OPEN) {
            open(State.HALF_OPEN);
            return;
        }
        if (consecutiveFailures.incrementAndGet() >= failureThreshold) {
            open(State.CLOSED);
        }
    }

    public State state() {
        return state.get();
    }

    private void open(State expectedState) {
        long now = System.nanoTime();
        openedAtNanos = now;
        if (state.compareAndSet(expectedState, State.OPEN)) {
            consecutiveFailures.set(0);
        }
    }
}
