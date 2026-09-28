package yunqi.courier.api.bootstrap;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncServiceReferenceTest {

    @Test
    void streamHonorsDemandAndInvokesOnlyOnce() throws Exception {
        AtomicInteger invocations = new AtomicInteger();
        AsyncServiceReference<Object> reference = new AsyncServiceReference<>() {
            @Override
            @SuppressWarnings("unchecked")
            public <R> CompletableFuture<R> invoke(String methodName, Object... arguments) {
                invocations.incrementAndGet();
                return (CompletableFuture<R>) CompletableFuture.completedFuture(List.of(1, 2, 3));
            }
        };
        List<Integer> received = new CopyOnWriteArrayList<>();
        AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();
        CountDownLatch firstValue = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);

        reference.<Integer>stream("values").subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription value) {
                subscription.set(value);
                value.request(1);
            }

            @Override
            public void onNext(Integer item) {
                received.add(item);
                firstValue.countDown();
            }

            @Override
            public void onError(Throwable throwable) {
                completed.countDown();
            }

            @Override
            public void onComplete() {
                completed.countDown();
            }
        });

        assertTrue(firstValue.await(2, TimeUnit.SECONDS));
        assertEquals(List.of(1), received);
        subscription.get().request(2);
        assertTrue(completed.await(2, TimeUnit.SECONDS));
        assertEquals(List.of(1, 2, 3), received);
        assertEquals(1, invocations.get());
    }

    @Test
    void nonPositiveDemandFailsWithoutInvoking() throws Exception {
        AtomicInteger invocations = new AtomicInteger();
        AsyncServiceReference<Object> reference = new AsyncServiceReference<>() {
            @Override
            public <R> CompletableFuture<R> invoke(String methodName, Object... arguments) {
                invocations.incrementAndGet();
                return CompletableFuture.completedFuture(null);
            }
        };
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch terminated = new CountDownLatch(1);

        reference.<Object>stream("value").subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(0);
            }

            @Override
            public void onNext(Object item) {
            }

            @Override
            public void onError(Throwable throwable) {
                failure.set(throwable);
                terminated.countDown();
            }

            @Override
            public void onComplete() {
                terminated.countDown();
            }
        });

        assertTrue(terminated.await(2, TimeUnit.SECONDS));
        assertTrue(failure.get() instanceof IllegalArgumentException);
        assertEquals(0, invocations.get());
    }
}
