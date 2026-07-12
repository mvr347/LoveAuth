package me.lovelace.loveAuth.session;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import me.lovelace.loveAuth.LoveAuth;
import me.lovelace.loveAuth.config.ConfigManager;
import me.lovelace.loveAuth.database.DatabaseManager;
import me.lovelace.loveAuth.security.SecurityUtils;

import javax.crypto.SecretKey;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class SessionManager {
    private final LoveAuth plugin;
    private final ConfigManager config;
    private final DatabaseManager database;
    private final SecretKey masterKey;
    private Cache<UUID, SessionData> sessions;

    public SessionManager(LoveAuth plugin, ConfigManager config, DatabaseManager database, SecretKey masterKey) {
        this.plugin = plugin;
        this.config = config;
        this.database = database;
        this.masterKey = masterKey;
        this.reload();
        database.deleteExpiredSessions();
    }

    public void reload() {
        this.sessions = Caffeine.newBuilder()
                .expireAfterWrite(28, TimeUnit.DAYS)
                .build();
    }

    public CompletableFuture<Boolean> isValid(UUID uuid, String ip) {
        if (!config.isSessionEnabled()) return CompletableFuture.completedFuture(false);
        long now = Instant.now().getEpochSecond();
        String ipHash = SecurityUtils.hashIp(ip, masterKey);
        SessionData cached = sessions.getIfPresent(uuid);
        if (cached != null) {
            if (cached.expiresAt <= now) {
                sessions.invalidate(uuid);
                return database.deleteSession(uuid).thenApply(unused -> false);
            }
            return CompletableFuture.completedFuture(!config.isSessionBindToIp() || cached.ipHash.equals(ipHash));
        }
        return database.getSession(uuid).thenCompose(record -> {
            if (record.isEmpty()) return CompletableFuture.completedFuture(false);
            DatabaseManager.SessionRecord session = record.get();
            if (session.expiresAt() <= now) {
                sessions.invalidate(uuid);
                return database.deleteSession(uuid).thenApply(unused -> false);
            }
            sessions.put(uuid, new SessionData(session.ipHash(), session.expiresAt()));
            return CompletableFuture.completedFuture(!config.isSessionBindToIp() || session.ipHash().equals(ipHash));
        });
    }

    public CompletableFuture<Void> create(UUID uuid, String ip) {
        return database.findPlayer(uuid).thenCompose(record -> {
            int durationDays = record.map(DatabaseManager.PlayerRecord::sessionDuration).orElse(config.getSessionDurationDays());
            if (durationDays <= 0) return CompletableFuture.completedFuture(null);
            
            long expiresAt = Instant.now().plusSeconds(TimeUnit.DAYS.toSeconds(durationDays)).getEpochSecond();
            String ipHash = SecurityUtils.hashIp(ip, masterKey);
            sessions.put(uuid, new SessionData(ipHash, expiresAt));
            return database.saveSession(uuid, ip, expiresAt);
        });
    }

    public CompletableFuture<Void> invalidate(UUID uuid) {
        sessions.invalidate(uuid);
        return database.deleteSession(uuid);
    }

    public CompletableFuture<Optional<Long>> getExpiry(UUID uuid) {
        SessionData cached = sessions.getIfPresent(uuid);
        long now = Instant.now().getEpochSecond();
        if (cached != null) {
            if (cached.expiresAt <= now) {
                sessions.invalidate(uuid);
                return database.deleteSession(uuid).thenApply(unused -> Optional.empty());
            }
            return CompletableFuture.completedFuture(Optional.of(cached.expiresAt));
        }
        return database.getSession(uuid).thenApply(record -> record
                .filter(session -> session.expiresAt() > now)
                .map(DatabaseManager.SessionRecord::expiresAt)
        );
    }

    public CompletableFuture<Void> saveActiveSessions() {
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        sessions.asMap().forEach((uuid, data) -> futures.add(database.saveKnownSession(new DatabaseManager.SessionRecord(uuid, data.ipHash, data.expiresAt))));
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
    }

    /**
     * Same as {@link #saveActiveSessions}, but writes synchronously on the calling thread
     * instead of going through the plugin's async scheduler. Must be used during onDisable() -
     * Bukkit/Paper/Folia refuse to schedule new async tasks once the plugin is marked disabled,
     * which it always is by the time onDisable() runs, so the async version silently fails there.
     */
    public void saveActiveSessionsSync() {
        sessions.asMap().forEach((uuid, data) -> {
            try {
                database.saveKnownSessionSync(new DatabaseManager.SessionRecord(uuid, data.ipHash, data.expiresAt));
            } catch (SQLException e) {
                plugin.getLogManager().errorKey("log.database-error", Map.of("message", safeMessage(e)), e);
            }
        });
    }

    private String safeMessage(Throwable throwable) {
        return throwable == null || throwable.getMessage() == null ? throwable.getClass().getSimpleName() : throwable.getMessage();
    }

    private record SessionData(String ipHash, long expiresAt) {}
}
