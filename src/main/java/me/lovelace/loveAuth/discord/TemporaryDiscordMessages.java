package me.lovelace.loveAuth.discord;

import java.util.concurrent.CompletionStage;
import java.util.function.BiConsumer;

/** Pending prompts are not timed here; only resolved status cards are short-lived. */
final class TemporaryDiscordMessages {
  private final BiConsumer<String, String> remove;
  private final BiConsumer<Runnable, Long> schedule;

  TemporaryDiscordMessages(BiConsumer<String, String> remove, BiConsumer<Runnable, Long> schedule) {
    this.remove = remove;
    this.schedule = schedule;
  }

  void resolved(String owner, String message, CompletionStage<?> update) {
    update.whenComplete(
        (result, error) -> {
          if (error != null || result == null) {
            remove.accept(owner, message);
            return;
          }
          schedule.accept(() -> remove.accept(owner, message), 2L);
        });
  }
}
