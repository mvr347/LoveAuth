package me.lovelace.loveAuth.discord;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

class LinkCodesTest {
    @Test
    void repeatedRequestsReuseOneCodeAndItIsConsumedExactlyOnce() {
        var store = new LinkCodes();
        var player = UUID.randomUUID();
        String code = store.issue(player);
        for (int i = 0; i < 1000; i++) assertEquals(code, store.issue(player));
        assertEquals(player, store.consume("  " + code.toLowerCase() + "  "));
        assertNull(store.consume(code));
        assertNotEquals(code, store.issue(player));
    }

    @Test
    void expiryAndShutdownRejectOldCodes() {
        var clock = new AtomicLong();
        var store = new LinkCodes(clock::get);
        var player = UUID.randomUUID();
        String code = store.issue(player);
        clock.set(Duration.ofMinutes(10).toNanos());
        assertFalse(store.contains(code));
        assertNull(store.consume(code));
        String fresh = store.issue(player);
        assertNotEquals(code, fresh);
        store.clear();
        assertNull(store.consume(fresh));
    }

    @Test
    void stoppedAdapterRejectsPendingLoginAndMainThreadRequests() {
        var manager = new DiscordAuthManager(null, null, null, null, null);
        manager.shutdown();
        assertFalse(manager.isEnabled());
        assertFalse(manager.requestDiscordLogin(null).join());
        assertThrows(
                java.util.concurrent.CancellationException.class,
                () -> manager.onMain(() -> true).join());
        assertThrows(
                IllegalStateException.class, () -> manager.generateLinkCode(UUID.randomUUID()));
    }
}
