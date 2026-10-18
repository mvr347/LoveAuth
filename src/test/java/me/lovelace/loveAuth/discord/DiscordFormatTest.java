package me.lovelace.loveAuth.discord;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DiscordFormatTest {

    @Test
    void presenceSubstitutesPlaceholders() {
        assertEquals("Играет: 12/100", DiscordFormat.presenceText("Играет: {online}/{max}", 12, 100));
        assertEquals("12 из 100 — 12", DiscordFormat.presenceText("{online} из {max} — {online}", 12, 100));
    }

    @Test
    void blankPresenceFormatFallsBackToDefault() {
        assertEquals("Играет: 0/20", DiscordFormat.presenceText("  ", 0, 20));
        assertEquals("Играет: 0/20", DiscordFormat.presenceText(null, 0, 20));
    }

    @Test
    void presenceIsCutToDiscordLimit() {
        String text = DiscordFormat.presenceText("x".repeat(300), 1, 2);
        assertEquals(DiscordFormat.PRESENCE_MAX_LENGTH, text.length());
    }

    @Test
    void onlineNamesAreSortedAndLimited() {
        List<String> names = new ArrayList<>(List.of("bob", "Alice", "carl"));
        assertEquals("`Alice`, `bob`, `carl`", DiscordFormat.onlineNames(names, 50, null));
        assertEquals("`Alice`, `bob`, и ещё 1", DiscordFormat.onlineNames(names, 2, "и ещё 1"));
        assertEquals(List.of("bob", "Alice", "carl"), names, "input list must not be reordered");
    }

    @Test
    void lastLoginIsDiscordTimestampOrFallback() {
        assertEquals("<t:1700000000:R>", DiscordFormat.lastLogin(1_700_000_000_999L, "нет"));
        assertEquals("нет", DiscordFormat.lastLogin(0, "нет"));
    }

    @Test
    void fieldValueHandlesEmptyAndOverlong() {
        assertEquals("—", DiscordFormat.fieldValue(List.of(), "—"));
        assertEquals("a, b", DiscordFormat.fieldValue(List.of("a", "b"), "—"));
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 500; i++) many.add("player" + i);
        assertTrue(DiscordFormat.fieldValue(many, "—").length() <= DiscordFormat.FIELD_MAX_LENGTH);
    }
}
