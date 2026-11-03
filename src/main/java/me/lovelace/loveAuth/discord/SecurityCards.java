package me.lovelace.loveAuth.discord;

import java.util.*;

/** Discord supports native button styles; the pending login keeps its familiar embed. */
final class SecurityCards {
  static Map<String, Object> button(String id, String label) {
    int style =
        id.contains(":yes:") || id.endsWith(":unlock")
            ? 3
            : id.contains(":no:")
                    || id.endsWith(":lock")
                    || id.endsWith(":unlink")
                    || id.endsWith(":sessions")
                ? 4
                : id.equals("lc:menu:home") ? 2 : 1;
    return Map.of("type", 2, "style", style, "custom_id", id, "label", label);
  }

  static Map<String, Object> login(
      String title, String description, String nonce, String confirm, String deny) {
    return Map.of(
        "content",
        "",
        "embeds",
        List.of(Map.of("title", title, "description", description, "color", 0xa855f7)),
        "allowed_mentions",
        Map.of("parse", List.of()),
        "components",
        List.of(
            Map.of(
                "type",
                1,
                "components",
                List.of(
                    button("la:core:yes:" + nonce, confirm),
                    button("la:core:no:" + nonce, deny)))));
  }
}
