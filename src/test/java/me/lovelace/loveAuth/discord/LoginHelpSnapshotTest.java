package me.lovelace.loveAuth.discord;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class LoginHelpSnapshotTest {
  @Test
  void responseContainsOnlyOwnerDiagnosticsAndSuppressesStaleOfflinePendingState() {
    var status = LoginHelpSnapshot.of(UUID.randomUUID(), "Player", false, false, true, true);
    assertEquals(
        Set.of(
            "available",
            "linked",
            "uuid",
            "name",
            "locked",
            "online",
            "authenticated",
            "confirmationPending"),
        status.keySet());
    assertEquals(false, status.get("authenticated"));
    assertEquals(false, status.get("confirmationPending"));
  }

  @Test
  void authenticatedPlayersDoNotSeeAnObsoleteLoginPrompt() {
    var status = LoginHelpSnapshot.of(UUID.randomUUID(), "Player", false, true, true, true);
    assertEquals(true, status.get("authenticated"));
    assertEquals(false, status.get("confirmationPending"));
  }
}
