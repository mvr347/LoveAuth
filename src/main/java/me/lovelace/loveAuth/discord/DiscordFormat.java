package me.lovelace.loveAuth.discord;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Pure text helpers for the bot (no JDA/Bukkit), kept separate so they can be unit-tested. */
final class DiscordFormat {
    private DiscordFormat() {}

    static final String DEFAULT_PRESENCE_FORMAT = "Играет: {online}/{max}";
    /** Discord cuts bot activity names at 128 characters. */
    static final int PRESENCE_MAX_LENGTH = 128;
    /** Embed field values may not exceed 1024 characters. */
    static final int FIELD_MAX_LENGTH = 1024;
    static final int ONLINE_LIST_LIMIT = 50;

    /** Renders the presence text; a blank template falls back to the default one. */
    static String presenceText(String format, int online, int max) {
        String template = (format == null || format.isBlank()) ? DEFAULT_PRESENCE_FORMAT : format;
        String text = template.replace("{online}", Integer.toString(online)).replace("{max}", Integer.toString(max));
        return truncate(text, PRESENCE_MAX_LENGTH);
    }

    /**
     * Sorted nick list in code spans (underscores in nicks would otherwise become italics), at most
     * {@code limit} names followed by the already-localised {@code moreText} when the list was cut.
     */
    static String onlineNames(List<String> names, int limit, String moreText) {
        List<String> sorted = new ArrayList<>(names);
        sorted.sort(Comparator.comparing(String::toLowerCase));
        int shown = Math.min(limit, sorted.size());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < shown; i++) {
            if (i > 0) sb.append(", ");
            sb.append('`').append(sorted.get(i)).append('`');
        }
        if (sorted.size() > shown && moreText != null) sb.append(", ").append(moreText);
        return sb.toString();
    }

    /** Discord relative timestamp, or {@code never} when no login was recorded. */
    static String lastLogin(long epochMillis, String never) {
        if (epochMillis <= 0) return never;
        return "<t:" + (epochMillis / 1000L) + ":R>";
    }

    /** Joins values for an embed field: empty becomes {@code empty}, overlong is cut with an ellipsis. */
    static String fieldValue(List<String> values, String empty) {
        if (values == null || values.isEmpty()) return empty;
        return truncate(String.join(", ", values), FIELD_MAX_LENGTH);
    }

    static String truncate(String text, int max) {
        if (text == null || text.length() <= max) return text;
        return text.substring(0, Math.max(0, max - 1)) + "…";
    }
}
