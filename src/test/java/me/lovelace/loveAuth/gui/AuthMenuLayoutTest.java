package me.lovelace.loveAuth.gui;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import me.lovelace.loveAuth.gui.AuthMenuLayout.Menu;
import me.lovelace.loveAuth.gui.AuthMenuLayout.Zone;
import org.junit.jupiter.api.Test;

class AuthMenuLayoutTest {
    private static Set<Integer> slots(Menu menu, Zone zone) {
        Set<Integer> out = new HashSet<>();
        for (int s = 0; s < AuthMenuLayout.SIZE; s++) if (AuthMenuLayout.zoneOf(menu, s).orElse(null) == zone) out.add(s);
        return out;
    }

    @Test
    void theTwoButtonMenuHasTwoDisjointThreeByThreeZonesLikeTheConfirmMenu() {
        assertEquals(Set.of(1, 2, 3, 10, 11, 12, 19, 20, 21), slots(Menu.REGISTER, Zone.PASSWORD));
        assertEquals(Set.of(5, 6, 7, 14, 15, 16, 23, 24, 25), slots(Menu.REGISTER, Zone.DISCORD));
        assertTrue(slots(Menu.REGISTER, Zone.SKIP).isEmpty());
        assertEquals(Optional.empty(), AuthMenuLayout.zoneOf(Menu.REGISTER, 4));
        assertEquals(Optional.empty(), AuthMenuLayout.zoneOf(Menu.REGISTER, 26));
    }

    @Test
    void thePremiumMenuHasThreeSquaresThatCoverEverySlotOnce() {
        assertEquals(Set.of(0, 1, 2, 9, 10, 11, 18, 19, 20), slots(Menu.PREMIUM_REGISTER, Zone.PASSWORD));
        assertEquals(Set.of(3, 4, 5, 12, 13, 14, 21, 22, 23), slots(Menu.PREMIUM_REGISTER, Zone.DISCORD));
        assertEquals(Set.of(6, 7, 8, 15, 16, 17, 24, 25, 26), slots(Menu.PREMIUM_REGISTER, Zone.SKIP));
        for (int s = 0; s < AuthMenuLayout.SIZE; s++) assertTrue(AuthMenuLayout.zoneOf(Menu.PREMIUM_REGISTER, s).isPresent(), "slot " + s);
    }

    @Test
    void theItemIdHasNoDeluxeMenusPrefixAndTitlesUseTheImageNames() {
        assertEquals("voidcore:invisible", AuthMenuLayout.INVISIBLE_ITEM);
        assertEquals("voidcore:invisible", AuthMenuLayout.itemId("itemsadder-voidcore:invisible"));
        assertTrue(Menu.REGISTER.title().contains("%img_register_menu%"));
        assertTrue(Menu.PREMIUM_REGISTER.title().contains("%img_premium_register_menu%"));
    }

    @Test
    void anUnresolvedPictureKeepsTheSmallWindow() {
        assertTrue(AuthMenuLayout.backgroundResolved(""));
        assertFalse(AuthMenuLayout.backgroundResolved("%img_register_menu%"));
        assertFalse(AuthMenuLayout.backgroundResolved(":register_menu:"));
        assertFalse(AuthMenuLayout.backgroundResolved(""));
    }

    @Test
    void theConfirmMenuHasConfirmAndCancelSquares() {
        AuthMenuLayout.Menu m = AuthMenuLayout.Menu.CONFIRM;
        org.junit.jupiter.api.Assertions.assertEquals(java.util.Optional.of(AuthMenuLayout.Zone.CONFIRM), AuthMenuLayout.zoneOf(m, 11));
        org.junit.jupiter.api.Assertions.assertEquals(java.util.Optional.of(AuthMenuLayout.Zone.CANCEL), AuthMenuLayout.zoneOf(m, 15));
        org.junit.jupiter.api.Assertions.assertTrue(AuthMenuLayout.zoneOf(m, 4).isEmpty());
    }
}
