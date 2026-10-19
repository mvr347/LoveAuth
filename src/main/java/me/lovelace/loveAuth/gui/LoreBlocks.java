package me.lovelace.loveAuth.gui;

import me.lovelace.loveAuth.lang.LangManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Lore building blocks of the GUI style standard (gui_gen v2.1): the separator, one click line per action,
 * the block of a disabled button and the progress bar. The wording lives in {@code gui.style.*} of lang.yml;
 * this class only assembles it, so every menu gets the same layout and the same colours.
 */
public final class LoreBlocks {
    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final int BAR_CELLS = 16;

    /** Mouse click a line belongs to; {@link #key} is the lang key under {@code gui.style.click}. */
    public enum Click {
        LEFT("left", "ЛКМ"), RIGHT("right", "ПКМ");

        final String key;
        final String label;

        Click(String key, String label) {
            this.key = key;
            this.label = label;
        }
    }

    /** One click line: which click does what ({@code action} is already localised text). */
    public record Hint(Click click, String action) {
    }

    private final Function<String, String> texts;

    public LoreBlocks(LangManager lang) {
        this(lang::get);
    }

    /** Source of raw lang texts; the constructor above is the production one, this one lets tests read lang.yml directly. */
    LoreBlocks(Function<String, String> texts) {
        this.texts = texts;
    }

    /** Left-click hint whose action text is the lang key {@code actionKey}. */
    public Hint left(String actionKey) {
        return new Hint(Click.LEFT, texts.apply(actionKey));
    }

    /** Right-click hint whose action text is the lang key {@code actionKey}. */
    public Hint right(String actionKey) {
        return new Hint(Click.RIGHT, texts.apply(actionKey));
    }

    /** Separator followed by one line per hint. No hints give no lines, so a separator never dangles. */
    public List<Component> clicks(Hint... hints) {
        if (hints.length == 0) return List.of();
        List<Component> lines = new ArrayList<>(hints.length + 1);
        lines.add(component("gui.style.separator", Map.of()));
        for (Hint hint : hints) {
            lines.add(component("gui.style.click." + hint.click().key, Map.of("action", hint.action())));
        }
        return lines;
    }

    /**
     * Block of a disabled button: separator, status line, {@code Причина:} line (the reason is the lang key
     * {@code reasonKey}) and the click that would have worked, shown as blocked. Always in this order.
     */
    public List<Component> blocked(String reasonKey, Hint blockedHint) {
        return List.of(
                component("gui.style.separator", Map.of()),
                component("gui.style.status.unavailable", Map.of()),
                component("gui.style.reason", Map.of("reason", texts.apply(reasonKey))),
                component("gui.style.blocked-click", Map.of("click", blockedHint.click().label, "action", blockedHint.action())));
    }

    /** Description lines first, then a block (clicks or a disabled block). Either part may be empty. */
    public static List<Component> join(List<Component> description, List<Component> block) {
        List<Component> lore = new ArrayList<>(description.size() + block.size());
        lore.addAll(description);
        lore.addAll(block);
        return lore;
    }

    /**
     * 16-cell bar with the percent, in the standard look: gold while it fills, green when full. Returns MiniMessage
     * text meant for a {@code {bar}} placeholder of a lore line.
     */
    public static String bar(long current, long max) {
        long top = Math.max(1, max);
        long value = Math.max(0, Math.min(current, top));
        int filled = (int) (value * BAR_CELLS / top);
        int percent = (int) (value * 100 / top);
        String fill = percent >= 100 ? "green" : "gold";
        return "<dark_gray>[<" + fill + ">" + "█".repeat(filled) + "<dark_gray>" + "█".repeat(BAR_CELLS - filled)
                + "<dark_gray>] <dark_gray>(<" + fill + ">" + percent + "%<dark_gray>)";
    }

    private Component component(String key, Map<String, String> placeholders) {
        String raw = texts.apply(key);
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            raw = raw.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return MINI.deserialize(raw);
    }
}
