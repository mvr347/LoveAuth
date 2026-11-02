package me.lovelace.loveAuth.discord;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class CoreAuthRequestTest {
  @Test
  void confirmationIsBoundToOwnerMessageAndExclusiveDeadline() {
    var request =
        new CoreAuthMenu.Request(UUID.randomUUID(), "owner", "LOGIN", "127.0.0.1", "card", 100);
    assertTrue(request.matches("owner", "card", 99));
    assertFalse(request.matches("other", "card", 99));
    assertFalse(request.matches("owner", "old-card", 99));
    assertFalse(request.matches("owner", null, 99));
    assertFalse(request.matches("owner", "card", 100));
  }
}
