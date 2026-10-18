package me.lovelace.loveAuth.discord;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ActionTokenTest {

    private final UUID owner = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");

    @Test
    void roundTrip() {
        ActionToken t = new ActionToken("confirm_action", owner, "REMOVE_PASSWORD", "AB12CD34EF56");
        ActionToken parsed = ActionToken.parse(t.toId()).orElseThrow();
        assertEquals(t, parsed);
        assertTrue(parsed.oneTime());
    }

    @Test
    void panelTokenWithoutOwnerIsStatic() {
        ActionToken t = new ActionToken("panel", null, "MENU", ActionToken.STATIC_NONCE);
        ActionToken parsed = ActionToken.parse(t.toId()).orElseThrow();
        assertNull(parsed.owner());
        assertFalse(parsed.oneTime());
    }

    @Test
    void longestIdFitsDiscordLimit() {
        ActionToken t = new ActionToken("confirm_action", owner, "REMOVE_PASSWORD", "ABCDEFGHJKLM");
        assertTrue(t.toId().length() <= ActionToken.MAX_ID_LENGTH);
    }

    @Test
    void malformedIdsAreRejectedWithoutThrowing() {
        assertTrue(ActionToken.parse(null).isEmpty());
        assertTrue(ActionToken.parse("").isEmpty());
        assertTrue(ActionToken.parse("confirm_action:not-a-uuid:LOCK:abc").isEmpty());
        assertTrue(ActionToken.parse("confirm_action:" + owner + ":LOCK").isEmpty());
        assertTrue(ActionToken.parse("confirm_action:" + owner + ":LOCK:abc:extra").isEmpty());
        assertTrue(ActionToken.parse("confirm_action:" + owner + "::abc").isEmpty());
        assertTrue(ActionToken.parse("confirm_action:" + owner + ":LOCK:").isEmpty());
        assertTrue(ActionToken.parse("x".repeat(101)).isEmpty());
    }

    @Test
    void oldFormatLoginIdIsNotAnActionToken() {
        assertTrue(ActionToken.parse("action_lock:" + owner).isEmpty());
    }
}
