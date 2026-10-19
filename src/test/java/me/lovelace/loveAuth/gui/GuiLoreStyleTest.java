package me.lovelace.loveAuth.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the GUI lore standard (gui_gen v2.1) over the menu texts of lang.yml (every {@code gui.*} key), and over
 * the blocks LoreBlocks builds from the {@code gui.style.*} keys.
 */
class GuiLoreStyleTest {

    private static final String SEPARATOR = "<dark_gray><st>─────────────────────</st>";
    private static final String SEPARATOR_PLAIN = "─────────────────────";
    private static final List<String> FORBIDDEN = List.of("Нажмите", "›", "▸", "•", "ЛКМ -", "ПКМ -", "✘", "✗", "✔", "⌛", "·");
    private static final Pattern SECTION = Pattern.compile("^<dark_gray>▪ <gray>\\S.*");
    private static final Pattern INDENT = Pattern.compile("^( {2}| {4})\\S.*");

    private static YamlConfiguration lang() {
        return YamlConfiguration.loadConfiguration(new InputStreamReader(
                GuiLoreStyleTest.class.getResourceAsStream("/lang/lang.yml"), StandardCharsets.UTF_8));
    }

    /** One text of the gui section: a plain string is one line, a list gives one line per entry. */
    private record Text(String path, String line) {
    }

    private static List<Text> texts(YamlConfiguration lang) {
        List<Text> out = new ArrayList<>();
        for (String key : lang.getKeys(true)) {
            if (!key.startsWith("gui.")) continue;
            if (lang.isList(key)) {
                for (String line : lang.getStringList(key)) out.add(new Text(key, line));
            } else if (lang.isString(key)) {
                out.add(new Text(key, lang.getString(key)));
            }
        }
        return out;
    }

    /** Keys of the gui section that end with {@code suffix} and hold a single string. */
    private static List<String> stringKeysEndingWith(YamlConfiguration lang, String suffix) {
        List<String> out = new ArrayList<>();
        for (String key : lang.getKeys(true)) {
            if (key.startsWith("gui.") && key.endsWith(suffix) && lang.isString(key)) out.add(key);
        }
        return out;
    }

    private static LoreBlocks blocks(YamlConfiguration lang) {
        return new LoreBlocks(key -> lang.getString(key, key));
    }

    private static List<String> plain(List<Component> lines) {
        PlainTextComponentSerializer serializer = PlainTextComponentSerializer.plainText();
        return lines.stream().map(serializer::serialize).toList();
    }

    @Test
    void theMenuTextIsSeenAndEveryLineParses() {
        List<Text> texts = texts(lang());
        assertTrue(texts.size() > 80, "the check must see the menu texts, saw " + texts.size());
        for (Text text : texts) {
            assertDoesNotThrow(() -> MiniMessage.miniMessage().deserialize(text.line()), text.path());
        }
    }

    @Test
    void forbiddenMarksAndPhrasesAreGone() {
        List<String> offenders = new ArrayList<>();
        for (Text text : texts(lang())) {
            for (String bad : FORBIDDEN) {
                if (text.line().contains(bad)) offenders.add(text.path() + " contains " + bad + ": " + text.line());
            }
        }
        assertTrue(offenders.isEmpty(), "forbidden in menu text: " + offenders);
    }

    @Test
    void clickLinesAreBuiltByLoreBlocksNotWrittenInLang() {
        // A click line typed into a lore list would skip the separator and the colour rules of LoreBlocks.
        List<String> offenders = new ArrayList<>();
        for (Text text : texts(lang())) {
            if (text.path().startsWith("gui.style.")) continue;
            if (text.line().contains("ЛКМ") || text.line().contains("ПКМ")) offenders.add(text.path() + ": " + text.line());
        }
        assertTrue(offenders.isEmpty(), "click lines belong to gui.style.click: " + offenders);
    }

    @Test
    void clickColoursAreTheStandard() {
        YamlConfiguration lang = lang();
        assertEquals("<green>ЛКМ <dark_gray>— <gray>{action}", lang.getString("gui.style.click.left"));
        assertEquals("<gold>ПКМ <dark_gray>— <gray>{action}", lang.getString("gui.style.click.right"));
        assertEquals("<dark_gray>{click} — {action} (заблокировано)", lang.getString("gui.style.blocked-click"));
        for (Text text : texts(lang)) {
            assertFalse(text.line().contains("<red>ЛКМ") || text.line().contains("<red>ПКМ"), text.path() + ": " + text.line());
        }
    }

    @Test
    void separatorIsTheSharedOneAndNothingElseDrawsARule() {
        YamlConfiguration lang = lang();
        assertEquals(SEPARATOR, lang.getString("gui.style.separator"));
        for (Text text : texts(lang)) {
            assertTrue(!text.line().contains("─") || text.line().equals(SEPARATOR), text.path() + " draws its own rule: " + text.line());
        }
    }

    @Test
    void separatorComesBeforeTheFirstClickOfEveryAction() {
        YamlConfiguration lang = lang();
        LoreBlocks blocks = blocks(lang);
        List<String> actionKeys = stringKeysEndingWith(lang, "-action");
        assertFalse(actionKeys.isEmpty());
        for (String key : actionKeys) {
            assertEquals(List.of(SEPARATOR_PLAIN, "ЛКМ — " + lang.getString(key)), plain(blocks.clicks(blocks.left(key))), key);
        }
        assertEquals(List.of(SEPARATOR_PLAIN, "ЛКМ — добавить день", "ПКМ — убрать день"),
                plain(blocks.clicks(blocks.left("gui.account.session-add-action"), blocks.right("gui.account.session-remove-action"))));
        assertTrue(blocks.clicks().isEmpty(), "no action, no separator");
    }

    @Test
    void disabledButtonsShowStatusReasonAndBlockedClickInThatOrder() {
        YamlConfiguration lang = lang();
        LoreBlocks blocks = blocks(lang);
        List<String> reasonKeys = stringKeysEndingWith(lang, "-locked-reason");
        assertFalse(reasonKeys.isEmpty());
        for (String reasonKey : reasonKeys) {
            String actionKey = reasonKey.replace("-reason", "-action");
            assertEquals(List.of(SEPARATOR_PLAIN, "✕ Действие недоступно", "Причина: " + lang.getString(reasonKey),
                    "ЛКМ — " + lang.getString(actionKey) + " (заблокировано)"), plain(blocks.blocked(reasonKey, blocks.left(actionKey))), reasonKey);
        }
    }

    @Test
    void theBlockLinesComeFromLoreBlocksAndAreNotWrittenInLang() {
        // Status, reason and blocked-click are built by LoreBlocks.blocked; a copy typed into lang.yml would drift.
        List<String> offenders = new ArrayList<>();
        for (Text text : texts(lang())) {
            if (text.path().startsWith("gui.style.")) continue;
            if (text.line().contains("Причина:") || text.line().contains("(заблокировано)") || text.line().contains("✕")) {
                offenders.add(text.path() + ": " + text.line());
            }
        }
        assertTrue(offenders.isEmpty(), "write the block through LoreBlocks: " + offenders);
    }

    @Test
    void theSharedBlockTextsKeepTheirStandardForm() {
        YamlConfiguration lang = lang();
        assertEquals("<red>✕ Действие недоступно", lang.getString("gui.style.status.unavailable"));
        assertEquals("<red>Причина: <gray>{reason}", lang.getString("gui.style.reason"));
    }

    @Test
    void loreOpensWithASubtitleAndItsSectionsAreFormatted() {
        YamlConfiguration lang = lang();
        List<String> offenders = new ArrayList<>();
        for (String key : lang.getKeys(true)) {
            if (!key.startsWith("gui.") || !key.endsWith("-lore") || !lang.isList(key)) continue;
            List<String> lines = lang.getStringList(key);
            if (!lines.get(0).startsWith("<dark_gray>")) offenders.add(key + ": no dark_gray subtitle first");
            for (String line : lines) {
                if (line.startsWith(" ") && !INDENT.matcher(line).matches()) offenders.add(key + ": indent must be 2 or 4 spaces: " + line);
                if (line.contains("▪") && !SECTION.matcher(line).matches()) offenders.add(key + ": section header format: " + line);
            }
        }
        assertTrue(offenders.isEmpty(), String.join("\n", offenders));
    }

    @Test
    void namesWithAStateUseTheDarkGrayBar() {
        // «Имя | Состояние»: the bar is dark_gray, the state colour follows it.
        List<String> offenders = new ArrayList<>();
        for (Text text : texts(lang())) {
            if (text.line().contains("|") && !text.line().contains("<dark_gray>| ")) offenders.add(text.path() + ": " + text.line());
        }
        assertTrue(offenders.isEmpty(), String.join("\n", offenders));
    }

    @Test
    void langVersionIsBumpedSoInstallsGetTheRestyle() {
        // refreshGuiSection replaces the gui section of an install only when the bundled lang-version is newer.
        assertTrue(lang().getInt("lang-version") >= 3, "the restyle needs lang-version 3 or newer");
    }
}
