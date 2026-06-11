package me.lovelace.loveAuth.auth;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import me.lovelace.loveAuth.LoveAuth;
import me.lovelace.loveAuth.api.events.AccountLockEvent;
import me.lovelace.loveAuth.config.ConfigManager;
import me.lovelace.loveAuth.database.DatabaseManager;
import me.lovelace.loveAuth.security.SecurityUtils;
import me.lovelace.loveAuth.util.LogManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class BruteForceProtection {
    private final LoveAuth plugin;
    private final ConfigManager config;
    private final DatabaseManager database;
    private final SecretKey masterKey;
    private final LogManager log;
    private final Cache<String, Integer> attempts = Caffeine.newBuilder()
            .expireAfterWrite(2, TimeUnit.HOURS)
            .build();

    public BruteForceProtection(LoveAuth plugin, ConfigManager config, DatabaseManager database, SecretKey masterKey, LogManager log) {
        this.plugin = plugin;
        this.config = config;
        this.database = database;
        this.masterKey = masterKey;
        this.log = log;
    }

    public CompletableFuture<IpBlockStatus> getIpBlockStatus(String ip) {
        long now = Instant.now().getEpochSecond();
        return database.getIpBlock(ip).thenCompose(record -> {
            if (record.isEmpty()) {
                return CompletableFuture.completedFuture(IpBlockStatus.allowed());
            }
            DatabaseManager.IpBlockRecord block = record.get();
            if (block.blockedUntil() > now) {
                long minutes = Math.max(1L, (block.blockedUntil() - now + 59L) / 60L);
                return CompletableFuture.completedFuture(IpBlockStatus.blocked(minutes));
            }
            return CompletableFuture.completedFuture(IpBlockStatus.allowed());
        }).exceptionally(error -> {
            log.errorKey("log.database-error", Map.of("message", safeMessage(error)), error);
            return IpBlockStatus.allowed();
        });
    }

    public CompletableFuture<FailureResult> recordFailure(Player player, UUID uuid, String ip) {
        long now = Instant.now().getEpochSecond();
        return database.getIpBlock(ip).thenCompose(record -> {
            int previous = record.map(DatabaseManager.IpBlockRecord::attemptCount).orElseGet(() -> attempts.get(ip, unused -> 0));
            long blockedUntil = record.map(DatabaseManager.IpBlockRecord::blockedUntil).orElse(0L);
            if (previous >= config.getMaxAttempts() && blockedUntil <= now && blockedUntil > 0L) {
                return lockAccount(player, uuid, ip);
            }
            int updated = previous + 1;
            attempts.put(ip, updated);
            if (updated >= config.getMaxAttempts()) {
                long until = now + TimeUnit.MINUTES.toSeconds(config.getLockoutDurationMinutes());
                return database.saveIpBlock(ip, until, updated).thenApply(unused -> {
                    callLockEvent(player, uuid, AccountLockEvent.LockReason.IP_BLOCKED);
                    log.warnKey("log.ip-blocked", Map.of("ip", ip, "minutes", Integer.toString(config.getLockoutDurationMinutes())));
                    log.database(uuid, "IP_BLOCKED", player.getName(), ip);
                    return FailureResult.ipBlocked(config.getLockoutDurationMinutes());
                });
            }
            return database.saveIpBlock(ip, 0L, updated).thenApply(unused -> FailureResult.failed(config.getMaxAttempts() - updated));
        }).exceptionally(error -> {
            log.errorKey("log.database-error", Map.of("message", safeMessage(error)), error);
            return FailureResult.failed(Math.max(0, config.getMaxAttempts() - 1));
        });
    }

    public CompletableFuture<Void> recordSuccess(String ip) {
        attempts.invalidate(ip);
        return database.clearIpBlock(ip).exceptionally(error -> {
            log.errorKey("log.database-error", Map.of("message", safeMessage(error)), error);
            return null;
        });
    }

    public CompletableFuture<Void> unlockAccount(UUID uuid) {
        return database.setLocked(uuid, false).exceptionally(error -> {
            log.errorKey("log.database-error", Map.of("message", safeMessage(error)), error);
            return null;
        });
    }

    private CompletableFuture<FailureResult> lockAccount(Player player, UUID uuid, String ip) {
        return database.setLocked(uuid, true).thenApply(unused -> {
            callLockEvent(player, uuid, AccountLockEvent.LockReason.ACCOUNT_LOCKED);
            log.warnKey("log.account-locked", Map.of("player", player.getName(), "reason", "ACCOUNT_LOCKED"));
            log.database(uuid, "ACCOUNT_LOCKED", player.getName(), ip);
            return FailureResult.accountLocked();
        });
    }

    private void callLockEvent(Player player, UUID uuid, AccountLockEvent.LockReason reason) {
        Bukkit.getScheduler().runTask(plugin, () -> Bukkit.getPluginManager().callEvent(new AccountLockEvent(player, uuid, reason)));
    }

    private String safeMessage(Throwable throwable) {
        return throwable == null || throwable.getMessage() == null ? throwable.getClass().getSimpleName() : throwable.getMessage();
    }

    public record IpBlockStatus(boolean blocked, long minutes) {
        public static IpBlockStatus allowed() {
            return new IpBlockStatus(false, 0L);
        }

        public static IpBlockStatus blocked(long minutes) {
            return new IpBlockStatus(true, minutes);
        }
    }

    public record FailureResult(FailureType type, int remainingAttempts, long lockMinutes) {
        public static FailureResult failed(int remainingAttempts) {
            return new FailureResult(FailureType.FAILED, Math.max(0, remainingAttempts), 0L);
        }

        public static FailureResult ipBlocked(long lockMinutes) {
            return new FailureResult(FailureType.IP_BLOCKED, 0, lockMinutes);
        }

        public static FailureResult accountLocked() {
            return new FailureResult(FailureType.ACCOUNT_LOCKED, 0, 0L);
        }
    }

    public enum FailureType {
        FAILED,
        IP_BLOCKED,
        ACCOUNT_LOCKED
    }
}
