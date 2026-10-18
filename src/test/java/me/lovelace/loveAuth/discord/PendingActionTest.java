package me.lovelace.loveAuth.discord;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PendingActionTest {

    private final UUID owner = UUID.randomUUID();
    private final PendingAction pending = new PendingAction(42L, owner, "UNLINK", "nonce1");

    private ActionToken token(UUID o, String action, String nonce) {
        return new ActionToken("confirm_action", o, action, nonce);
    }

    @Test
    void acceptsExactMatch() {
        assertTrue(pending.matches(42L, token(owner, "UNLINK", "nonce1")));
    }

    @Test
    void rejectsAnyMismatch() {
        assertFalse(pending.matches(43L, token(owner, "UNLINK", "nonce1")));
        assertFalse(pending.matches(42L, token(UUID.randomUUID(), "UNLINK", "nonce1")));
        assertFalse(pending.matches(42L, token(owner, "LOCK_ACCOUNT", "nonce1")));
        assertFalse(pending.matches(42L, token(owner, "UNLINK", "other")));
        assertFalse(pending.matches(42L, token(null, "UNLINK", "nonce1")));
        assertFalse(pending.matches(42L, null));
    }
}
