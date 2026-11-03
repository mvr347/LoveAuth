package me.lovelace.loveAuth.discord;

import java.util.*;

/** Deliberately narrow response for the private Discord help menu. */
final class LoginHelpSnapshot {
  static Map<String, Object> of(
      UUID uuid,
      String name,
      boolean locked,
      boolean online,
      boolean authenticated,
      boolean pending) {
    return Map.of(
        "available",
        true,
        "linked",
        true,
        "uuid",
        uuid.toString(),
        "name",
        name,
        "locked",
        locked,
        "online",
        online,
        "authenticated",
        online && authenticated,
        "confirmationPending",
        online && pending && !authenticated);
  }
}
