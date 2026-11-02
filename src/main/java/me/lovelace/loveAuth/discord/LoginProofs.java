package me.lovelace.loveAuth.discord;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Registered before asynchronous lookups; quitting also invalidates replies not yet sent. */
final class LoginProofs {
  private final ConcurrentHashMap<UUID, String> latest = new ConcurrentHashMap<>();

  String begin(UUID id) {
    String nonce = UUID.randomUUID().toString().replace("-", "");
    latest.put(id, nonce);
    return nonce;
  }

  boolean current(UUID id, String nonce) {
    return nonce != null && nonce.equals(latest.get(id));
  }

  void cancel(UUID id) {
    latest.remove(id);
  }

  void cancel(UUID id, String nonce) {
    latest.remove(id, nonce);
  }

  void clear() {
    latest.clear();
  }
}
