package me.lovelace.loveAuth.discord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class BotLinkTest {
    @Test
    void theCoreIdWinsAndIsLinkedToTheBotProfile() {
        assertEquals(Optional.of("https://discord.com/users/123456789012345678"),
                BotLink.profileUrl("123456789012345678", "999999999999999999"));
    }

    @Test
    void theConfiguredIdIsUsedWhenTheCoreDoesNotKnowOne() {
        assertEquals(Optional.of("https://discord.com/users/987654321098765432"),
                BotLink.profileUrl(null, " 987654321098765432 "));
    }

    @Test
    void noValidIdMeansNoLink() {
        assertTrue(BotLink.profileUrl(null, "").isEmpty());
        assertTrue(BotLink.profileUrl("", null).isEmpty());
        assertTrue(BotLink.profileUrl("abc", "12").isEmpty());
        assertTrue(BotLink.profileUrl("1 2 3 4 5", "https://evil.example/").isEmpty(), "only digits are accepted");
    }

    @Test
    void onlyAPlayerWhoHasNotLoggedInGetsTheWayBackToTheMenu() {
        assertTrue(DiscordAuthManager.showsMenuReturn(false));
        org.junit.jupiter.api.Assertions.assertFalse(DiscordAuthManager.showsMenuReturn(true));
    }
}
