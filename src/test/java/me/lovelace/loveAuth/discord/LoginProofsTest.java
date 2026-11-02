package me.lovelace.loveAuth.discord;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class LoginProofsTest {
  @Test
  void lateDatabaseReplyCannotPublishARequestAfterQuit() {
    var requests = new LoginProofs();
    var id = UUID.randomUUID();
    String nonce = requests.begin(id);
    var database = new CompletableFuture<String>();
    var maySend = database.thenApply(value -> requests.current(id, nonce));
    requests.cancel(id);
    database.complete("linked-discord");
    assertFalse(maySend.join());
  }

  @Test
  void lateReplyFromPreviousConnectionCannotReplaceTheNewRequest() {
    var requests = new LoginProofs();
    var id = UUID.randomUUID();
    String old = requests.begin(id);
    requests.cancel(id);
    String current = requests.begin(id);
    assertFalse(requests.current(id, old));
    assertTrue(requests.current(id, current));
    requests.cancel(id, old);
    assertTrue(requests.current(id, current));
    requests.clear();
    assertFalse(requests.current(id, current));
  }
}
