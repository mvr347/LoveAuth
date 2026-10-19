package me.lovelace.loveAuth.gui;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuiLayoutTest {

    @Test
    void centeredRowIsSymmetricAroundTheMiddleOfTheRow() {
        assertArrayEquals(new int[]{13}, GuiManager.centeredRow(9, 1));
        assertArrayEquals(new int[]{12, 14}, GuiManager.centeredRow(9, 2));
        assertArrayEquals(new int[]{11, 13, 15}, GuiManager.centeredRow(9, 3));
        assertArrayEquals(new int[]{20, 22, 24}, GuiManager.centeredRow(18, 3));
        for (int count = 1; count <= 7; count++) {
            int[] slots = GuiManager.centeredRow(9, count);
            assertEquals(count, slots.length);
            for (int i = 0; i < slots.length; i++) {
                assertEquals(26, slots[i] + slots[slots.length - 1 - i], "count " + count);
                assertTrue(slots[i] > 9 && slots[i] < 17, "side walls stay empty");
            }
        }
    }

    @Test
    void registerAndPremiumLayoutsStayCentredWhenDiscordIsOff() {
        assertArrayEquals(new int[]{12, 14}, RegisterGui.slots(true));
        assertArrayEquals(new int[]{13}, RegisterGui.slots(false));
        assertArrayEquals(new int[]{11, 13, 15}, PremiumWelcomeGui.slots(true));
        assertArrayEquals(new int[]{12, 14}, PremiumWelcomeGui.slots(false));
    }

    @Test
    void centeredRowRejectsCountsThatDoNotFit() {
        assertThrows(IllegalArgumentException.class, () -> GuiManager.centeredRow(9, 8));
        assertThrows(IllegalArgumentException.class, () -> GuiManager.centeredRow(9, -1));
        assertEquals(0, GuiManager.centeredRow(9, 0).length);
        assertEquals(7, GuiManager.centeredRow(9, 7).length);
    }

    @Test
    void accountLayoutIsSymmetricForOneToSixButtons() {
        for (int count = 1; count <= 6; count++) {
            GuiManager.Layout layout = GuiManager.accountLayout(count);
            assertEquals(count <= 3 ? 27 : 36, layout.size(), "size for " + count);
            assertEquals(count, layout.slots().length);
            Set<Integer> all = new HashSet<>();
            java.util.Map<Integer, Set<Integer>> columnsByRow = new java.util.TreeMap<>();
            for (int slot : layout.slots()) {
                assertTrue(all.add(slot), "no slot used twice");
                assertTrue(slot >= 9 && slot < layout.size() - 9, "buttons stay in the work zone: " + slot);
                assertTrue(slot % 9 >= 1 && slot % 9 <= 7, "side walls stay empty: " + slot);
                columnsByRow.computeIfAbsent(slot / 9, r -> new HashSet<>()).add(slot % 9);
            }
            for (var row : columnsByRow.entrySet()) {
                for (int col : row.getValue()) {
                    assertTrue(row.getValue().contains(8 - col),
                            "row " + row.getKey() + " is not symmetric around column 4 for " + count + " buttons");
                }
            }
        }
    }

    @Test
    void accountLayoutMatchesThePlanTable() {
        assertArrayEquals(new int[]{13}, GuiManager.accountLayout(1).slots());
        assertArrayEquals(new int[]{12, 14}, GuiManager.accountLayout(2).slots());
        assertArrayEquals(new int[]{11, 13, 15}, GuiManager.accountLayout(3).slots());
        assertArrayEquals(new int[]{12, 14, 21, 23}, GuiManager.accountLayout(4).slots());
        assertArrayEquals(new int[]{11, 13, 15, 21, 23}, GuiManager.accountLayout(5).slots());
        assertArrayEquals(new int[]{11, 13, 15, 20, 22, 24}, GuiManager.accountLayout(6).slots());
    }

    @Test
    void accountLayoutRejectsMoreThanSixButtons() {
        assertThrows(IllegalArgumentException.class, () -> GuiManager.accountLayout(7));
        assertThrows(IllegalArgumentException.class, () -> GuiManager.accountLayout(8));
        assertThrows(IllegalArgumentException.class, () -> GuiManager.accountLayout(-1));
    }

    @Test
    void accountMenuHidesWhatIsUnavailable() {
        // no password, no Discord, Discord integration on
        assertEquals(List.of(AccountGui.Action.CHANGE_PASSWORD, AccountGui.Action.DISCORD, AccountGui.Action.LOGOUT),
                AccountGui.visibleActions(false, false, true));
        // Discord integration off: nothing Discord-related, whatever the account has
        assertEquals(List.of(AccountGui.Action.CHANGE_PASSWORD, AccountGui.Action.SESSION, AccountGui.Action.LOGOUT),
                AccountGui.visibleActions(true, true, false));
        // everything available
        assertEquals(6, AccountGui.visibleActions(true, true, true).size());
        // password but no Discord: no delete-password, no method switch
        assertEquals(List.of(AccountGui.Action.CHANGE_PASSWORD, AccountGui.Action.SESSION,
                AccountGui.Action.DISCORD, AccountGui.Action.LOGOUT), AccountGui.visibleActions(true, false, true));
        for (boolean p : new boolean[]{false, true}) for (boolean d : new boolean[]{false, true}) for (boolean e : new boolean[]{false, true}) {
            assertTrue(AccountGui.visibleActions(p, d, e).size() <= 6);
        }
    }

    @Test
    void slotActionsAreReplacedAtOnce() {
        SlotActions<String> actions = new SlotActions<>();
        assertTrue(actions.at(13).isEmpty());
        actions.replace(java.util.Map.of(13, "a"));
        assertEquals("a", actions.at(13).orElseThrow());
        actions.replace(java.util.Map.of(12, "b"));
        assertTrue(actions.at(13).isEmpty(), "a redraw must forget the old layout");
    }

    private static YamlConfiguration lang() {
        return YamlConfiguration.loadConfiguration(new InputStreamReader(
                GuiLayoutTest.class.getResourceAsStream("/lang/lang.yml"), StandardCharsets.UTF_8));
    }

    private static boolean isEmojiLike(int cp) {
        if (cp >= 0x1F000) return true;
        if (cp >= 0x2300 && cp <= 0x23FF) return true;                 // clocks, hourglass
        // dingbats; only the state marks ✓ and ✕ are part of the GUI standard (gui_gen v2.1)
        return cp >= 0x2600 && cp <= 0x27BF && cp != 0x2713 && cp != 0x2715;
    }

    @Test
    void menuTextHasNoEmojiOrBold() {
        YamlConfiguration lang = lang();
        List<String> offenders = new ArrayList<>();
        int checked = 0;
        for (String key : lang.getKeys(true)) {
            if (!key.startsWith("gui.")) continue;
            checked++;
            List<String> values = new ArrayList<>();
            if (lang.isList(key)) values.addAll(lang.getStringList(key));
            else if (lang.isString(key)) values.add(lang.getString(key));
            for (String value : values) {
                if (value.contains("<bold>") || value.contains("<gradient")) offenders.add(key + " (bold/gradient)");
                if (value.codePoints().anyMatch(GuiLayoutTest::isEmojiLike)) offenders.add(key + " (emoji)");
            }
        }
        assertTrue(checked > 50, "the style check must actually see the menu text, saw " + checked);
        assertTrue(offenders.isEmpty(), "menu text must stay plain: " + offenders);
    }

    @Test
    void menuTitlesAreUnique() {
        YamlConfiguration lang = lang();
        Set<String> seen = new HashSet<>();
        for (String key : lang.getKeys(true)) {
            if (!key.startsWith("gui.") || !key.endsWith(".title") || !lang.isString(key)) continue;
            String plain = lang.getString(key).replaceAll("<[^>]*>", "");
            assertTrue(seen.add(plain), "duplicate menu title: " + plain);
        }
        assertTrue(seen.size() >= 7, "expected every menu title to be checked, got " + seen);
    }

    @Test
    void everyGuiKeyUsedByTheCodeExists() throws Exception {
        YamlConfiguration lang = lang();
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\"(gui\\.[a-z0-9.-]*[a-z0-9])\"");
        try (var files = java.nio.file.Files.walk(java.nio.file.Path.of("src/main/java"))) {
            // config.yml keys such as gui.account.show-profile share the prefix but are not lang keys
            for (java.nio.file.Path file : files.filter(f -> f.toString().endsWith(".java") && !f.toString().replace('\\', '/').contains("/config/")).toList()) {
                var matcher = pattern.matcher(java.nio.file.Files.readString(file));
                while (matcher.find()) {
                    assertTrue(lang.isSet(matcher.group(1)), "missing lang key " + matcher.group(1) + " used in " + file);
                }
            }
        }
    }
}
