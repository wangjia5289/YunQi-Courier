package yunqi.courier.network.netty;

import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;
import yunqi.courier.common.protocol.CourierResponse;
import yunqi.courier.network.netty.client.PendingRequestManager;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PendingRequestManagerTest {

    @Test
    void shouldEnforcePendingRequestBoundAtomically() {
        PendingRequestManager manager = new PendingRequestManager();
        EmbeddedChannel channel = new EmbeddedChannel();
        CompletableFuture<CourierResponse> first = new CompletableFuture<>();
        CompletableFuture<CourierResponse> second = new CompletableFuture<>();

        assertTrue(manager.tryPut(1, first, channel, 1));
        assertFalse(manager.tryPut(2, second, channel, 1));
        assertEquals(1, manager.size());

        assertEquals(first, manager.remove(1));
        assertTrue(manager.tryPut(2, second, channel, 1));
        assertEquals(1, manager.size());
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldRejectDuplicateRequestIdWithoutReplacingOriginalFuture() {
        PendingRequestManager manager = new PendingRequestManager();
        EmbeddedChannel channel = new EmbeddedChannel();
        CompletableFuture<CourierResponse> first = new CompletableFuture<>();
        CompletableFuture<CourierResponse> duplicate = new CompletableFuture<>();

        assertTrue(manager.tryPut(7, first, channel, 2));
        assertFalse(manager.tryPut(7, duplicate, channel, 2));
        assertEquals(1, manager.size());
        assertEquals(first, manager.remove(7));
        assertFalse(duplicate.isDone());
        channel.finishAndReleaseAll();
    }
}
