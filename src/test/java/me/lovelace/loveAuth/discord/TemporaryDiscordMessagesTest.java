package me.lovelace.loveAuth.discord;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class TemporaryDiscordMessagesTest {
  @Test
  void resultIsVisibleForTwoSecondsAfterUpdateCompletes() {
    var deleted = new ArrayList<String>();
    var scheduled = new ArrayList<Runnable>();
    var delay = new ArrayList<Long>();
    var cleanup =
        new TemporaryDiscordMessages(
            (owner, id) -> deleted.add(owner + ":" + id),
            (action, seconds) -> {
              scheduled.add(action);
              delay.add(seconds);
            });
    var update = new CompletableFuture<String>();
    cleanup.resolved("owner", "card", update);
    assertTrue(deleted.isEmpty());
    assertTrue(scheduled.isEmpty());
    update.complete("card");
    assertEquals(List.of(2L), delay);
    assertTrue(deleted.isEmpty());
    scheduled.getFirst().run();
    assertEquals(List.of("owner:card"), deleted);
  }

  @Test
  void failedUpdateDoesNotLeaveAnInactivePromptBehind() {
    var deleted = new ArrayList<String>();
    var cleanup =
        new TemporaryDiscordMessages(
            (owner, id) -> deleted.add(id),
            (action, seconds) -> fail("No delay for failed update"));
    cleanup.resolved("owner", "card", CompletableFuture.failedFuture(new IllegalStateException()));
    assertEquals(List.of("card"), deleted);
  }

  @Test
  void rejectedDiscordUpdateIsAlsoRemoved() {
    var deleted = new ArrayList<String>();
    var cleanup =
        new TemporaryDiscordMessages(
            (owner, id) -> deleted.add(id),
            (action, seconds) -> fail("No delay for rejected update"));
    cleanup.resolved("owner", "card", CompletableFuture.completedFuture(null));
    assertEquals(List.of("card"), deleted);
  }
}
