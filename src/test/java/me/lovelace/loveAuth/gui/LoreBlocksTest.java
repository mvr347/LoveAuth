package me.lovelace.loveAuth.gui;

import me.lovelace.loveAuth.textures.HeadTextures;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoreBlocksTest {

    private static final String SEPARATOR = "─────────────────────";
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private static YamlConfiguration lang() {
        return YamlConfiguration.loadConfiguration(new InputStreamReader(
                LoreBlocksTest.class.getResourceAsStream("/lang/lang.yml"), StandardCharsets.UTF_8));
    }

    /** The real style texts from lang.yml, plus one reason key that exists only in this test. */
    private static LoreBlocks blocks() {
        YamlConfiguration lang = lang();
        return new LoreBlocks(key -> key.equals("test.reason") ? "Пароль не установлен" : lang.getString(key, key));
    }

    private static List<String> plain(List<Component> lines) {
        return lines.stream().map(PLAIN::serialize).toList();
    }

    private static String barText(long current, long max) {
        return PLAIN.serialize(MiniMessage.miniMessage().deserialize(LoreBlocks.bar(current, max)));
    }

    @Test
    void clicksStartWithTheSeparatorAndHoldOneActionPerLine() {
        List<Component> lines = blocks().clicks(
                new LoreBlocks.Hint(LoreBlocks.Click.LEFT, "ввести пароль"),
                new LoreBlocks.Hint(LoreBlocks.Click.RIGHT, "убрать день"));
        assertEquals(List.of(SEPARATOR, "ЛКМ — ввести пароль", "ПКМ — убрать день"), plain(lines));
    }

    @Test
    void noHintsMeansNoSeparatorEither() {
        assertTrue(blocks().clicks().isEmpty());
    }

    @Test
    void disabledButtonBlockIsStatusReasonThenBlockedClick() {
        List<String> lines = plain(blocks().blocked("test.reason",
                new LoreBlocks.Hint(LoreBlocks.Click.LEFT, "войти по паролю")));
        assertEquals(List.of(SEPARATOR, "✕ Действие недоступно", "Причина: Пароль не установлен",
                "ЛКМ — войти по паролю (заблокировано)"), lines);
    }

    @Test
    void hintTakesItsActionFromTheKeyLookup() {
        LoreBlocks texts = new LoreBlocks(key -> "текст " + key);
        assertEquals(new LoreBlocks.Hint(LoreBlocks.Click.RIGHT, "текст a.b"), texts.right("a.b"));
    }

    @Test
    void barIsSixteenCellsGoldUntilFullAndGreenAtHundredPercent() {
        assertEquals("<dark_gray>[<gold>" + "█".repeat(8) + "<dark_gray>" + "█".repeat(8)
                + "<dark_gray>] <dark_gray>(<gold>50%<dark_gray>)", LoreBlocks.bar(14, 28));
        assertEquals("[" + "█".repeat(16) + "] (100%)", barText(28, 28));
        assertTrue(LoreBlocks.bar(28, 28).contains("<green>100%"));
        assertEquals("[" + "█".repeat(16) + "] (100%)", barText(40, 28), "over the top clamps to full");
        assertEquals("[" + "█".repeat(16) + "] (0%)", barText(0, 28));
    }

    @Test
    void joinPutsTheBlockAfterTheDescription() {
        assertEquals(List.of("a", "b", "c"),
                plain(LoreBlocks.join(List.of(Component.text("a")), List.of(Component.text("b"), Component.text("c")))));
    }

    @Test
    void italicsAreRemovedUnlessTheTextAskedForThem() {
        assertEquals(TextDecoration.State.FALSE, HeadTextures.upright(Component.text("имя")).decoration(TextDecoration.ITALIC));
        assertEquals(TextDecoration.State.TRUE,
                HeadTextures.upright(Component.text("имя").decoration(TextDecoration.ITALIC, true)).decoration(TextDecoration.ITALIC));
    }
}
