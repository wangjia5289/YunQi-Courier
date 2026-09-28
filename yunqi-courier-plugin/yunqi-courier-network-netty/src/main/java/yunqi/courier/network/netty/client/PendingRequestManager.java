package yunqi.courier.network.netty.client;

import yunqi.courier.common.protocol.CourierResponse;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import io.netty.channel.Channel;

public class PendingRequestManager {

    private final Map<Long, CompletableFuture<CourierResponse>> pendingRequestMap = new ConcurrentHashMap<>();

    private final Map<Long, ScheduledFuture<?>> timeoutTaskMap = new ConcurrentHashMap<>();

    private final Map<Long, Channel> requestChannelMap = new ConcurrentHashMap<>();

    public void put(long requestId, CompletableFuture<CourierResponse> future) {
        pendingRequestMap.put(requestId, future);
    }

    public void put(long requestId, CompletableFuture<CourierResponse> future, Channel channel) {
        pendingRequestMap.put(requestId, future);
        requestChannelMap.put(requestId, channel);
    }

    /**
     * Atomically reserves a pending slot and associates the request with its
     * channel. The synchronized section is intentionally tiny and prevents
     * concurrent callers from exceeding the configured client-side bound.
     */
    public synchronized boolean tryPut(long requestId, CompletableFuture<CourierResponse> future,
                                       Channel channel, int maxPendingRequests) {
        if (channel == null || !reserve(requestId, future, maxPendingRequests)) {
            return false;
        }
        if (associateChannel(requestId, channel)) {
            return true;
        }
        remove(requestId);
        return false;
    }

    /**
     * Reserves a pending slot before any potentially blocking connection
     * attempt. This keeps overload rejection fail-fast and race-free.
     */
    public synchronized boolean reserve(long requestId, CompletableFuture<CourierResponse> future,
                                        int maxPendingRequests) {
        if (future == null || maxPendingRequests <= 0
                || pendingRequestMap.containsKey(requestId)
                || pendingRequestMap.size() >= maxPendingRequests) {
            return false;
        }
        pendingRequestMap.put(requestId, future);
        return true;
    }

    public synchronized boolean associateChannel(long requestId, Channel channel) {
        if (channel == null || !pendingRequestMap.containsKey(requestId)) {
            return false;
        }
        requestChannelMap.put(requestId, channel);
        return true;
    }

    public int size() {
        return pendingRequestMap.size();
    }

    public synchronized CompletableFuture<CourierResponse> remove(long requestId) {
        ScheduledFuture<?> timeoutTask = timeoutTaskMap.remove(requestId);
        if (timeoutTask != null) {
            timeoutTask.cancel(false);
        }
        requestChannelMap.remove(requestId);
        return pendingRequestMap.remove(requestId);
    }

    public synchronized void setTimeoutTask(long requestId, ScheduledFuture<?> timeoutTask) {
        if (timeoutTask == null) {
            return;
        }
        if (pendingRequestMap.containsKey(requestId)) {
            timeoutTaskMap.put(requestId, timeoutTask);
        } else {
            timeoutTask.cancel(false);
        }
    }

    public void failAll(Throwable throwable) {
        pendingRequestMap.forEach((requestId, future) -> future.completeExceptionally(throwable));
        pendingRequestMap.clear();
        timeoutTaskMap.values().forEach(task -> task.cancel(false));
        timeoutTaskMap.clear();
        requestChannelMap.clear();
    }

    public void fail(Channel channel, Throwable throwable) {
        requestChannelMap.forEach((requestId, requestChannel) -> {
            if (requestChannel == channel) {
                CompletableFuture<CourierResponse> future = remove(requestId);
                if (future != null) {
                    future.completeExceptionally(throwable);
                }
            }
        });
    }
}
