package me.lovelace.loveAuth.listeners;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class MenuOpenClockTest {
    private final UUID player = UUID.randomUUID();

    @Test
    void unknownUntilTheFirstOpening() {
        assertEquals(-1L, new MenuOpenClock().sinceOpen(player, 5000));
    }

    @Test
    void aReopeningRequestedByTheListenerDoesNotRestartTheClock() {
        var clock = new MenuOpenClock();
        clock.opened(player, 1000);
        clock.reopening(player, 1800);
        clock.opened(player, 1850);
        assertEquals(1500L, clock.sinceOpen(player, 2500), "measured from the first opening, so Esc spam is kicked");
    }

    @Test
    void aFreshOpeningRestartsTheClock() {
        var clock = new MenuOpenClock();
        clock.opened(player, 1000);
        clock.opened(player, 9000);
        assertEquals(500L, clock.sinceOpen(player, 9500));
    }

    @Test
    void aReopenRequestThatNeverLandedDoesNotSwallowALaterFreshOpening() {
        var clock = new MenuOpenClock();
        clock.opened(player, 1000);
        clock.reopening(player, 1500);
        clock.opened(player, 9000);
        assertEquals(0L, clock.sinceOpen(player, 9000));
    }
}
