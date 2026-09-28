package yunqi.courier.api.bootstrap;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Iterator;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * Explicit asynchronous facade. It is intentionally separate from the
 * synchronous JDK proxy so existing service interfaces remain source and ABI
 * compatible.
 */
@FunctionalInterface
public interface AsyncServiceReference<T> {

    <R> CompletableFuture<R> invoke(String methodName, Object... arguments);

    default <R> CompletableFuture<R> invoke(Method method, Object... arguments) {
        Objects.requireNonNull(method, "method");
        return invokeExact(method, arguments);
    }

    /**
     * Invokes the supplied reflected method without losing its parameter types.
     * Implementations that only support name-based dispatch retain the
     * compatibility fallback below; the built-in bootstrap overrides this hook.
     */
    default <R> CompletableFuture<R> invokeExact(Method method, Object... arguments) {
        Objects.requireNonNull(method, "method");
        return invoke(method.getName(), arguments);
    }

    /**
     * Adapts a unary result that is an {@link Iterable} or {@link java.util.stream.Stream}
     * into a cancellable publisher. Providers that need wire-level
     * incremental frames can introduce a dedicated transport SPI without
     * changing this facade.
     */
    default <R> Flow.Publisher<R> stream(String methodName, Object... arguments) {
        Object[] invocationArguments = arguments == null ? new Object[0] : arguments.clone();
        return subscriber -> {
            Objects.requireNonNull(subscriber, "subscriber").onSubscribe(new Flow.Subscription() {
                private final Object lock = new Object();
                private final AtomicBoolean started = new AtomicBoolean();
                private final AtomicBoolean draining = new AtomicBoolean();
                private final AtomicBoolean terminated = new AtomicBoolean();
                private final AtomicLong demand = new AtomicLong();
                private volatile boolean cancelled;
                private volatile CompletableFuture<R> pending;
                private volatile Iterator<?> iterator;
                private volatile AutoCloseable closeable;

                @Override
                public void request(long requested) {
                    if (requested <= 0) {
                        invalidDemand();
                        return;
                    }
                    synchronized (lock) {
                        if (cancelled || terminated.get()) {
                            return;
                        }
                        addDemand(requested);
                    }
                    if (started.compareAndSet(false, true)) {
                        startInvocation();
                    }
                    drain();
                }

                @Override
                public void cancel() {
                    CompletableFuture<R> invocation;
                    synchronized (lock) {
                        if (cancelled || terminated.get()) {
                            return;
                        }
                        cancelled = true;
                        terminated.set(true);
                        invocation = pending;
                    }
                    if (invocation != null) {
                        invocation.cancel(true);
                    }
                    closeResource();
                }

                private void startInvocation() {
                    CompletableFuture<R> invocation;
                    try {
                        invocation = AsyncServiceReference.this.invoke(methodName, invocationArguments);
                        if (invocation == null) {
                            throw new IllegalStateException("Async invocation returned null future");
                        }
                    } catch (Throwable throwable) {
                        terminateError(throwable);
                        return;
                    }
                    boolean cancelNow;
                    synchronized (lock) {
                        cancelNow = cancelled || terminated.get();
                        if (!cancelNow) {
                            pending = invocation;
                        }
                    }
                    if (cancelNow) {
                        invocation.cancel(true);
                        return;
                    }
                    invocation.whenComplete((value, failure) -> {
                        if (failure != null) {
                            terminateError(failure);
                            return;
                        }
                        prepare(value);
                    });
                }

                private void prepare(Object value) {
                    Iterator<?> nextIterator;
                    AutoCloseable resource = null;
                    try {
                        if (value instanceof Stream<?> stream) {
                            resource = stream;
                            nextIterator = stream.iterator();
                        } else if (value instanceof Iterable<?> iterable) {
                            nextIterator = iterable.iterator();
                        } else {
                            nextIterator = Collections.singleton(value).iterator();
                        }
                    } catch (Throwable throwable) {
                        if (resource != null) {
                            try {
                                resource.close();
                            } catch (Exception ignored) {
                                throwable.addSuppressed(ignored);
                            }
                        }
                        terminateError(throwable);
                        return;
                    }
                    synchronized (lock) {
                        if (cancelled || terminated.get()) {
                            closeResource(resource);
                            return;
                        }
                        closeable = resource;
                        iterator = nextIterator;
                    }
                    drain();
                }

                private void drain() {
                    if (cancelled || terminated.get() || !draining.compareAndSet(false, true)) {
                        return;
                    }
                    // Completion callbacks can run on a Netty event loop. Iterate and
                    // invoke subscriber callbacks on the common pool instead.
                    ForkJoinPool.commonPool().execute(() -> {
                        try {
                            drainLoop();
                        } finally {
                            draining.set(false);
                            boolean continueDraining;
                            synchronized (lock) {
                                continueDraining = !cancelled && !terminated.get()
                                        && iterator != null && demand.get() > 0;
                            }
                            if (continueDraining) {
                                drain();
                            }
                        }
                    });
                }

                private void drainLoop() {
                    while (true) {
                        Iterator<?> current;
                        synchronized (lock) {
                            if (cancelled || terminated.get() || demand.get() == 0) {
                                return;
                            }
                            current = iterator;
                            if (current == null) {
                                return;
                            }
                        }
                        boolean hasNext;
                        try {
                            hasNext = current.hasNext();
                        } catch (Throwable throwable) {
                            terminateError(throwable);
                            return;
                        }
                        if (!hasNext) {
                            terminateComplete();
                            return;
                        }
                        Object value;
                        try {
                            value = current.next();
                        } catch (Throwable throwable) {
                            terminateError(throwable);
                            return;
                        }
                        synchronized (lock) {
                            if (cancelled || terminated.get() || demand.get() == 0) {
                                return;
                            }
                            demand.decrementAndGet();
                        }
                        if (value == null) {
                            terminateError(new NullPointerException("Flow.Publisher elements must not be null"));
                            return;
                        }
                        try {
                            @SuppressWarnings("unchecked") R element = (R) value;
                            subscriber.onNext(element);
                        } catch (Throwable throwable) {
                            terminateError(throwable);
                            return;
                        }
                        if (demand.get() == 0) {
                            try {
                                if (!current.hasNext()) {
                                    terminateComplete();
                                }
                            } catch (Throwable throwable) {
                                terminateError(throwable);
                            }
                            return;
                        }
                    }
                }

                private void invalidDemand() {
                    CompletableFuture<R> invocation;
                    synchronized (lock) {
                        if (cancelled || terminated.get()) {
                            return;
                        }
                        cancelled = true;
                        terminated.set(true);
                        invocation = pending;
                    }
                    if (invocation != null) {
                        invocation.cancel(true);
                    }
                    closeResource();
                    try {
                        subscriber.onError(new IllegalArgumentException("demand must be positive"));
                    } catch (Throwable ignored) {
                        // Subscriber failures must not leak transport resources.
                    }
                }

                private void terminateError(Throwable throwable) {
                    CompletableFuture<R> invocation;
                    synchronized (lock) {
                        if (cancelled || !terminated.compareAndSet(false, true)) {
                            return;
                        }
                        invocation = pending;
                    }
                    if (invocation != null) {
                        invocation.cancel(true);
                    }
                    closeResource();
                    try {
                        subscriber.onError(throwable);
                    } catch (Throwable ignored) {
                        // Subscriber failures must not leak transport resources.
                    }
                }

                private void terminateComplete() {
                    synchronized (lock) {
                        if (cancelled || !terminated.compareAndSet(false, true)) {
                            return;
                        }
                    }
                    closeResource();
                    try {
                        subscriber.onComplete();
                    } catch (Throwable ignored) {
                        // Subscriber failures are outside the publisher contract.
                    }
                }

                private void addDemand(long requested) {
                    long current = demand.get();
                    long updated = current + requested;
                    if (updated < 0 || updated < current) {
                        updated = Long.MAX_VALUE;
                    }
                    demand.set(updated);
                }

                private void closeResource() {
                    AutoCloseable resource;
                    synchronized (lock) {
                        resource = closeable;
                        closeable = null;
                    }
                    closeResource(resource);
                }

                private void closeResource(AutoCloseable resource) {
                    if (resource != null) {
                        try {
                            resource.close();
                        } catch (Exception ignored) {
                            // Resource cleanup is best effort after cancellation/termination.
                        }
                    }
                }
            });
        };
    }
}
