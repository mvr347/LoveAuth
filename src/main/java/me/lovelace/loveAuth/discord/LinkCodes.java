package me.lovelace.loveAuth.discord;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Locale;
import java.util.UUID;
import java.util.function.LongSupplier;

/** One bounded, expiring code per player; repeated clicks do not allocate scheduled tasks. */
final class LinkCodes {
    private final Cache<String, UUID> codes;
    private final Cache<UUID, String> players;
    private final SecureRandom random = new SecureRandom();

    LinkCodes() {
        this(System::nanoTime);
    }

    LinkCodes(LongSupplier clock) {
        codes =
                Caffeine.newBuilder()
                        .maximumSize(10_000)
                        .expireAfterWrite(Duration.ofMinutes(10))
                        .ticker(clock::getAsLong)
                        .build();
        players =
                Caffeine.newBuilder()
                        .maximumSize(10_000)
                        .expireAfterWrite(Duration.ofMinutes(10))
                        .ticker(clock::getAsLong)
                        .executor(Runnable::run)
                        .removalListener((UUID player, String code, com.github.benmanes.caffeine.cache.RemovalCause cause) -> { if (code != null) codes.invalidate(code); })
                        .build();
    }

    synchronized String issue(UUID player) {
        if (player == null) throw new IllegalArgumentException("Player is required");
        String previous = players.getIfPresent(player);
        if (previous != null && player.equals(codes.getIfPresent(previous))) return previous;
        String code;
        do {
            var value = new StringBuilder(8);
            String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
            for (int i = 0; i < 8; i++)
                value.append(alphabet.charAt(random.nextInt(alphabet.length())));
            code = value.toString();
        } while (codes.getIfPresent(code) != null);
        codes.put(code, player);
        players.put(player, code);
        return code;
    }

    synchronized boolean contains(String code) {
        return code != null && codes.getIfPresent(normalize(code)) != null;
    }

    synchronized UUID consume(String code) {
        if (code == null) return null;
        String key = normalize(code);
        UUID player = codes.asMap().remove(key);
        if (player != null) players.asMap().remove(player, key);
        return player;
    }

    synchronized void clear() {
        codes.invalidateAll();
        players.invalidateAll();
    }

    private String normalize(String code) {
        return code.trim().toUpperCase(Locale.ROOT);
    }
}
