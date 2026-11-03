package me.lovelace.loveAuth.discord;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class SecurityCardsTest {
  @Test
  void pendingLoginUsesThePreviousEmbedAndColoredOwnerBoundButtons() {
    var card =
        SecurityCards.login(
            "🔐 Запрос на вход",
            "Игрок Ada123 пытается войти. Это вы?",
            "nonce",
            "Подтвердить вход",
            "Отклонить");
    assertEquals("", card.get("content"));
    assertEquals(1, ((List<?>) card.get("embeds")).size());
    assertEquals(3, SecurityCards.button("la:core:yes:nonce", "Подтвердить вход").get("style"));
    assertEquals(4, SecurityCards.button("la:core:no:nonce", "Отклонить").get("style"));
    assertEquals(1, SecurityCards.button("la:core:password", "Пароль").get("style"));
    assertEquals(2, SecurityCards.button("lc:menu:home", "Главное меню").get("style"));
  }
}
