package me.lovelace.loveAuth.listeners;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * When a player's auth menu was first opened, for the "closed right after opening" grace window. A reopening
 * that the close listener itself triggers does not restart the clock: otherwise a player who presses Esc as soon
 * as the menu shows gets it back again and again, and is only kicked after a pause longer than the window.
 */
final class MenuOpenClock {
    /** A reopening is expected to land within this many ms of being requested. */
    static final long REOPEN_WINDOW_MS = 1000L;

    private final Map<UUID, Long> firstOpened = new ConcurrentHashMap<>();
    private final Map<UUID, Long> reopenRequested = new ConcurrentHashMap<>();

    /** The close listener is about to reopen this player's menu. */
    void reopening(UUID player, long now) {
        reopenRequested.put(player, now);
    }

    /** An auth menu was opened for the player. */
    void opened(UUID player, long now) {
        Long requested = reopenRequested.remove(player);
        boolean ourReopen = requested != null && now - requested <= REOPEN_WINDOW_MS;
        if (!ourReopen || !firstOpened.containsKey(player)) firstOpened.put(player, now);
    }

    /** Milliseconds since the first opening, or -1 when unknown. */
    long sinceOpen(UUID player, long now) {
        Long opened = firstOpened.get(player);
        return opened == null ? -1L : now - opened;
    }

    void forget(UUID player) {
        firstOpened.remove(player);
        reopenRequested.remove(player);
    }
}
