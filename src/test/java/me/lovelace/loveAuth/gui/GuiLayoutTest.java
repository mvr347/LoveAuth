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
    void accountGridIsThreeByTwoAndCentred() {
        int[] top = {AccountGui.SLOT_CHANGE_PASSWORD, AccountGui.SLOT_DELETE_PASSWORD, AccountGui.SLOT_SESSION};
        int[] bottom = {AccountGui.SLOT_LOGIN_METHOD, AccountGui.SLOT_DISCORD, AccountGui.SLOT_LOGOUT};
        assertArrayEquals(GuiManager.centeredRow(9, 3), top);
        assertArrayEquals(GuiManager.centeredRow(18, 3), bottom);
        assertEquals(34, AccountGui.BACK_SLOT);
        assertEquals(35, AccountGui.CLOSE_SLOT);
    }

    private static YamlConfiguration lang() {
        return YamlConfiguration.loadConfiguration(new InputStreamReader(
                GuiLayoutTest.class.getResourceAsStream("/lang/lang.yml"), StandardCharsets.UTF_8));
    }

    private static boolean isEmojiLike(int cp) {
        if (cp >= 0x1F000) return true;
        if (cp >= 0x2300 && cp <= 0x23FF) return true;                 // clocks, hourglass
        return cp >= 0x2600 && cp <= 0x27BF && cp != 0x2718;           // dingbats; only the cross mark is allowed
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
            for (java.nio.file.Path file : files.filter(f -> f.toString().endsWith(".java") && !f.toString().contains("/config/")).toList()) {
                var matcher = pattern.matcher(java.nio.file.Files.readString(file));
                while (matcher.find()) {
                    assertTrue(lang.isSet(matcher.group(1)), "missing lang key " + matcher.group(1) + " used in " + file);
                }
            }
        }
    }
}
