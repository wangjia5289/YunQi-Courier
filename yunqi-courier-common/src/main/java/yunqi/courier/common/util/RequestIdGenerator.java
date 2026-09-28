package yunqi.courier.common.util;

import java.util.concurrent.atomic.AtomicLong;

public final class RequestIdGenerator {

    private static final AtomicLong SEQUENCE = new AtomicLong(1);

    private RequestIdGenerator() {
    }

    public static long nextLongId() {
        return SEQUENCE.getAndIncrement();
    }

    public static String nextId() {
        return Long.toString(nextLongId());
    }
}
