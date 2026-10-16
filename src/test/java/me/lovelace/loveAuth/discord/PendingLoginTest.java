package me.lovelace.loveAuth.discord;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PendingLoginTest {

    private final PendingLogin p = new PendingLogin(42L, "1.2.3.4", "abc");

    @Test
    void requiresMessageAndNonce() {
        assertTrue(p.matchesRequest(42L, "abc"));
        assertFalse(p.matchesRequest(43L, "abc"));
        assertFalse(p.matchesRequest(42L, "zzz"));
        assertFalse(p.matchesRequest(42L, null));
    }

    @Test
    void requiresSameIp() {
        assertTrue(p.matchesIp("1.2.3.4"));
        assertFalse(p.matchesIp("5.6.7.8"));
        assertFalse(p.matchesIp(null));
    }
}
