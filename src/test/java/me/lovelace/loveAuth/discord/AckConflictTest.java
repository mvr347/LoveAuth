package me.lovelace.loveAuth.discord;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AckConflictTest {

    @Test
    void onlyAlreadyAcknowledgedIsSwallowed() {
        assertTrue(AckConflict.isAlreadyAcknowledged(40060));
        assertFalse(AckConflict.isAlreadyAcknowledged(10062)); // unknown interaction: a real problem
        assertFalse(AckConflict.isAlreadyAcknowledged(50013)); // missing permissions
    }
}
