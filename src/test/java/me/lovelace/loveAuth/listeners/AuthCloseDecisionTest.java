package me.lovelace.loveAuth.listeners;

import org.junit.jupiter.api.Test;

import static me.lovelace.loveAuth.listeners.AuthCloseDecision.Action.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

class AuthCloseDecisionTest {
    @Test
    void unknownAlwaysReopens() {
        assertEquals(REOPEN, AuthCloseDecision.decide(false, true, 100_000, 2000));
        assertEquals(REOPEN, AuthCloseDecision.decide(false, true, -1, 2000));
    }

    @Test
    void playerCloseWithinGraceReopens() {
        assertEquals(REOPEN, AuthCloseDecision.decide(true, false, 0, 2000));
        assertEquals(REOPEN, AuthCloseDecision.decide(true, false, 1999, 2000));
    }

    @Test
    void playerCloseAfterGraceKicks() {
        assertEquals(KICK, AuthCloseDecision.decide(true, false, 2000, 2000));
        assertEquals(KICK, AuthCloseDecision.decide(true, false, -1, 2000));
    }

    @Test
    void otherReasonsIgnored() {
        assertEquals(IGNORE, AuthCloseDecision.decide(false, false, 0, 2000));
    }
}
