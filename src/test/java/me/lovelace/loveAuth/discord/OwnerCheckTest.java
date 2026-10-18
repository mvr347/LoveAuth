package me.lovelace.loveAuth.discord;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class OwnerCheckTest {

    @Test
    void onlyTheLinkedPlayerIsOwner() {
        UUID a = UUID.randomUUID();
        assertTrue(OwnerCheck.isOwner(a, a));
        assertFalse(OwnerCheck.isOwner(a, UUID.randomUUID()));
    }

    @Test
    void unknownSidesNeverMatch() {
        UUID a = UUID.randomUUID();
        assertFalse(OwnerCheck.isOwner(null, a));
        assertFalse(OwnerCheck.isOwner(a, null));
        assertFalse(OwnerCheck.isOwner(null, null));
    }
}
