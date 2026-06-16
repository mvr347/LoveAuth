package me.lovelace.loveAuth.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import me.lovelace.loveAuth.LoveAuth;
import me.lovelace.loveAuth.config.ConfigManager;
import me.lovelace.loveAuth.input.InputMethod;
import me.lovelace.loveAuth.security.SecurityUtils;

import javax.crypto.SecretKey;
import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class DatabaseManager {
    private final LoveAuth plugin;
    private final ConfigManager configManager;
    private final SecretKey masterKey;
    private HikariDataSource dataSource;
    private CompletableFuture<Void> ready = new CompletableFuture<>();

    public DatabaseManager(LoveAuth plugin, ConfigManager configManager, SecretKey masterKey) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.masterKey = masterKey;
    }

    public CompletableFuture<Void> initialize() {
        plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
            try {
                File databaseFile = new File(plugin.getDataFolder(), configManager.getDatabaseFile());
                if (databaseFile.getParentFile() != null) databaseFile.getParentFile().mkdirs();

                HikariConfig hikariConfig = new HikariConfig();
                hikariConfig.setPoolName("LoveAuth-SQLite");
                hikariConfig.setJdbcUrl("jdbc:sqlite:" + databaseFile.getAbsolutePath());
                hikariConfig.setDriverClassName("org.sqlite.JDBC");
                hikariConfig.setMaximumPoolSize(4);
                hikariConfig.setConnectionTimeout(10000L);
                
                dataSource = new HikariDataSource(hikariConfig);

                try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
                    statement.execute("PRAGMA journal_mode=WAL;");
                    statement.execute("PRAGMA synchronous=NORMAL;");
                    createTables(statement);
                }
                ready.complete(null);
            } catch (Exception e) {
                ready.completeExceptionally(e);
            }
        });
        return ready;
    }

    private void createTables(Statement statement) throws SQLException {
        statement.execute("""
                CREATE TABLE IF NOT EXISTS players (
                    uuid TEXT PRIMARY KEY,
                    username TEXT NOT NULL,
                    password_hash TEXT,
                    discord_id TEXT,
                    discord_id_hash TEXT,
                    is_locked INTEGER NOT NULL DEFAULT 0,
                    password_enabled INTEGER NOT NULL DEFAULT 1,
                    input_method TEXT NOT NULL DEFAULT 'SIGN',
                    session_duration INTEGER DEFAULT 7,
                    registered_at INTEGER NOT NULL,
                    last_login INTEGER,
                    last_ip TEXT
                );
                """);
        statement.execute("""
                CREATE TABLE IF NOT EXISTS sessions (
                    uuid TEXT PRIMARY KEY,
                    ip_hash TEXT NOT NULL,
                    expires_at INTEGER NOT NULL
                );
                """);
        statement.execute("""
                CREATE TABLE IF NOT EXISTS ip_blocks (
                    ip_hash TEXT PRIMARY KEY,
                    blocked_until INTEGER NOT NULL,
                    attempt_count INTEGER NOT NULL DEFAULT 0,
                    raw_ip TEXT
                );
                """);
        statement.execute("""
                CREATE TABLE IF NOT EXISTS admin_passwords (
                    uuid TEXT PRIMARY KEY,
                    password_hash TEXT NOT NULL
                );
                """);
        statement.execute("""
                CREATE TABLE IF NOT EXISTS logs (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    timestamp INTEGER NOT NULL,
                    uuid TEXT,
                    action TEXT NOT NULL,
                    details TEXT,
                    ip_hash TEXT
                );
                """);
        try { statement.execute("ALTER TABLE players ADD COLUMN session_duration INTEGER DEFAULT 7;"); } catch (SQLException ignored) {}
        try { statement.execute("ALTER TABLE players ADD COLUMN last_ip TEXT;"); } catch (SQLException ignored) {}
        try { statement.execute("ALTER TABLE ip_blocks ADD COLUMN raw_ip TEXT;"); } catch (SQLException ignored) {}
        try { statement.execute("ALTER TABLE players ADD COLUMN discord_id_hash TEXT;"); } catch (SQLException ignored) {}
    }

    public CompletableFuture<Optional<PlayerRecord>> findPlayer(UUID uuid) {
        return supplyAsync(() -> {
            try (Connection connection = getConnection();
                 PreparedStatement statement = connection.prepareStatement("SELECT * FROM players WHERE uuid = ?")) {
                statement.setString(1, uuid.toString());
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) return Optional.empty();
                    return Optional.of(readPlayer(resultSet));
                }
            }
        });
    }

    public CompletableFuture<Optional<PlayerRecord>> findPlayerByName(String username) {
        return supplyAsync(() -> {
            try (Connection connection = getConnection();
                 PreparedStatement statement = connection.prepareStatement("SELECT * FROM players WHERE lower(username) = lower(?)")) {
                statement.setString(1, username);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) return Optional.empty();
                    return Optional.of(readPlayer(resultSet));
                }
            }
        });
    }

    public CompletableFuture<Optional<PlayerRecord>> findPlayerByDiscordId(String discordId) {
        String hash = SecurityUtils.hashIp(discordId, masterKey);
        return supplyAsync(() -> {
            try (Connection connection = getConnection();
                 PreparedStatement statement = connection.prepareStatement("SELECT * FROM players WHERE discord_id_hash = ?")) {
                statement.setString(1, hash);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) return Optional.empty();
                    return Optional.of(readPlayer(resultSet));
                }
            }
        });
    }

    public CompletableFuture<Boolean> isRegistered(UUID uuid) {
        return findPlayer(uuid).thenApply(Optional::isPresent);
    }

    public CompletableFuture<Boolean> isLocked(UUID uuid) {
        return findPlayer(uuid).thenApply(r -> r.map(PlayerRecord::locked).orElse(false));
    }

    public CompletableFuture<Void> setLocked(UUID uuid, boolean locked) {
        return supplyAsync(() -> {
            try (Connection connection = getConnection();
                 PreparedStatement statement = connection.prepareStatement("UPDATE players SET is_locked = ? WHERE uuid = ?")) {
                statement.setInt(1, locked ? 1 : 0);
                statement.setString(2, uuid.toString());
                statement.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<Void> unlockAllAccounts() {
        return supplyAsync(() -> {
            try (Connection connection = getConnection();
                 PreparedStatement statement = connection.prepareStatement("UPDATE players SET is_locked = 0")) {
                statement.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<Void> createPlayer(UUID uuid, String username, boolean premium, InputMethod inputMethod) {
        long now = Instant.now().getEpochSecond();
        return supplyAsync(() -> {
            try (Connection connection = getConnection();
                 PreparedStatement statement = connection.prepareStatement("""
                         INSERT INTO players (uuid, username, registered_at, input_method)
                         VALUES (?, ?, ?, ?)
                         ON CONFLICT(uuid) DO UPDATE SET username = excluded.username
                         """)) {
                statement.setString(1, uuid.toString());
                statement.setString(2, username);
                statement.setLong(3, now);
                statement.setString(4, inputMethod.name());
                statement.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<Void> registerPlayer(UUID uuid, String username, String passwordHash, boolean premium, InputMethod inputMethod) {
        long now = Instant.now().getEpochSecond();
        return supplyAsync(() -> {
            try (Connection connection = getConnection();
                 PreparedStatement statement = connection.prepareStatement("""
                         INSERT INTO players (uuid, username, password_hash, password_enabled, input_method, registered_at, last_login)
                         VALUES (?, ?, ?, 1, ?, ?, ?)
                         ON CONFLICT(uuid) DO UPDATE SET
                             password_hash = excluded.password_hash,
                             password_enabled = 1,
                             last_login = excluded.last_login
                         """)) {
                statement.setString(1, uuid.toString());
                statement.setString(2, username);
                statement.setString(3, passwordHash);
                statement.setString(4, inputMethod.name());
                statement.setLong(5, now);
                statement.setLong(6, now);
                statement.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<Void> deletePlayer(UUID uuid) {
        return supplyAsync(() -> {
            try (Connection connection = getConnection()) {
                for (String sql : List.of(
                    "DELETE FROM sessions WHERE uuid = ?",
                    "DELETE FROM admin_passwords WHERE uuid = ?",
                    "DELETE FROM logs WHERE uuid = ?",
                    "DELETE FROM players WHERE uuid = ?"
                )) {
                    try (PreparedStatement s = connection.prepareStatement(sql)) {
                        s.setString(1, uuid.toString());
                        s.executeUpdate();
                    }
                }
            }
            return null;
        });
    }

    public CompletableFuture<Void> updatePassword(UUID uuid, String passwordHash) {
        return supplyAsync(() -> {
            try (Connection connection = getConnection();
                 PreparedStatement statement = connection.prepareStatement("UPDATE players SET password_hash = ?, password_enabled = 1 WHERE uuid = ?")) {
                statement.setString(1, passwordHash);
                statement.setString(2, uuid.toString());
                statement.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<Void> setAdminPassword(UUID uuid, String passwordHash) {
        return supplyAsync(() -> {
            try (Connection connection = getConnection();
                 PreparedStatement statement = connection.prepareStatement("INSERT INTO admin_passwords (uuid, password_hash) VALUES (?, ?) ON CONFLICT(uuid) DO UPDATE SET password_hash = excluded.password_hash")) {
                statement.setString(1, uuid.toString());
                statement.setString(2, passwordHash);
                statement.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<Optional<String>> getAdminPassword(UUID uuid) {
        return supplyAsync(() -> {
            try (Connection connection = getConnection();
                 PreparedStatement statement = connection.prepareStatement("SELECT password_hash FROM admin_passwords WHERE uuid = ?")) {
                statement.setString(1, uuid.toString());
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next()) return Optional.of(resultSet.getString("password_hash"));
                }
            }
            return Optional.empty();
        });
    }

    public CompletableFuture<Void> setSessionDuration(UUID uuid, int days) {
        return supplyAsync(() -> {
            try (Connection connection = getConnection();
                 PreparedStatement statement = connection.prepareStatement("UPDATE players SET session_duration = ? WHERE uuid = ?")) {
                statement.setInt(1, days);
                statement.setString(2, uuid.toString());
                statement.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<Void> updateLastLogin(UUID uuid, String ip) {
        long now = Instant.now().getEpochSecond();
        return supplyAsync(() -> {
            try (Connection connection = getConnection();
                 PreparedStatement statement = connection.prepareStatement("UPDATE players SET last_login = ?, last_ip = ? WHERE uuid = ?")) {
                statement.setLong(1, now);
                statement.setString(2, ip);
                statement.setString(3, uuid.toString());
                statement.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<Void> setDiscordId(UUID uuid, String discordId) {
        String encrypted = discordId == null || discordId.isBlank() ? null : SecurityUtils.encrypt(discordId, masterKey);
        String hash = discordId == null || discordId.isBlank() ? null : SecurityUtils.hashIp(discordId, masterKey);
        return supplyAsync(() -> {
            try (Connection connection = getConnection();
                 PreparedStatement statement = connection.prepareStatement("UPDATE players SET discord_id = ?, discord_id_hash = ? WHERE uuid = ?")) {
                statement.setString(1, encrypted);
                statement.setString(2, hash);
                statement.setString(3, uuid.toString());
                statement.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<Void> setPasswordEnabled(UUID uuid, boolean enabled) {
        return supplyAsync(() -> {
            try (Connection connection = getConnection();
                 PreparedStatement statement = connection.prepareStatement("UPDATE players SET password_enabled = ? WHERE uuid = ?")) {
                statement.setInt(1, enabled ? 1 : 0);
                statement.setString(2, uuid.toString());
                statement.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<Void> setInputMethod(UUID uuid, InputMethod inputMethod) {
        return supplyAsync(() -> {
            try (Connection connection = getConnection();
                 PreparedStatement statement = connection.prepareStatement("UPDATE players SET input_method = ? WHERE uuid = ?")) {
                statement.setString(1, inputMethod.name());
                statement.setString(2, uuid.toString());
                statement.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<Optional<SessionRecord>> getSession(UUID uuid) {
        return supplyAsync(() -> {
            try (Connection connection = getConnection();
                 PreparedStatement statement = connection.prepareStatement("SELECT * FROM sessions WHERE uuid = ?")) {
                statement.setString(1, uuid.toString());
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) return Optional.empty();
                    return Optional.of(new SessionRecord(UUID.fromString(resultSet.getString("uuid")), resultSet.getString("ip_hash"), resultSet.getLong("expires_at")));
                }
            }
        });
    }

    public CompletableFuture<Void> saveSession(UUID uuid, String ip, long expiresAt) {
        String ipHash = SecurityUtils.hashIp(ip, masterKey);
        return supplyAsync(() -> {
            try (Connection connection = getConnection();
                 PreparedStatement statement = connection.prepareStatement("""
                         INSERT INTO sessions (uuid, ip_hash, expires_at)
                         VALUES (?, ?, ?)
                         ON CONFLICT(uuid) DO UPDATE SET ip_hash = excluded.ip_hash, expires_at = excluded.expires_at
                         """)) {
                statement.setString(1, uuid.toString());
                statement.setString(2, ipHash);
                statement.setLong(3, expiresAt);
                statement.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<Void> deleteSession(UUID uuid) {
        return supplyAsync(() -> {
            try (Connection connection = getConnection();
                 PreparedStatement statement = connection.prepareStatement("DELETE FROM sessions WHERE uuid = ?")) {
                statement.setString(1, uuid.toString());
                statement.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<Void> deleteExpiredSessions() {
        long now = Instant.now().getEpochSecond();
        return supplyAsync(() -> {
            try (Connection connection = getConnection();
                 PreparedStatement statement = connection.prepareStatement("DELETE FROM sessions WHERE expires_at <= ?")) {
                statement.setLong(1, now);
                statement.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<Optional<IpBlockRecord>> getIpBlock(String ip) {
        String ipHash = SecurityUtils.hashIp(ip, masterKey);
        return supplyAsync(() -> {
            try (Connection connection = getConnection();
                 PreparedStatement statement = connection.prepareStatement("SELECT * FROM ip_blocks WHERE ip_hash = ?")) {
                statement.setString(1, ipHash);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) return Optional.empty();
                    return Optional.of(new IpBlockRecord(resultSet.getString("ip_hash"), resultSet.getLong("blocked_until"), resultSet.getInt("attempt_count")));
                }
            }
        });
    }

    public CompletableFuture<Void> saveIpBlock(String ip, long blockedUntil, int attemptCount) {
        String ipHash = SecurityUtils.hashIp(ip, masterKey);
        return supplyAsync(() -> {
            try (Connection connection = getConnection();
                 PreparedStatement statement = connection.prepareStatement("""
                         INSERT INTO ip_blocks (ip_hash, blocked_until, attempt_count, raw_ip)
                         VALUES (?, ?, ?, ?)
                         ON CONFLICT(ip_hash) DO UPDATE SET blocked_until = excluded.blocked_until, attempt_count = excluded.attempt_count
                         """)) {
                statement.setString(1, ipHash);
                statement.setLong(2, blockedUntil);
                statement.setInt(3, attemptCount);
                statement.setString(4, ip);
                statement.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<Void> clearIpBlock(String ip) {
        String ipHash = SecurityUtils.hashIp(ip, masterKey);
        return supplyAsync(() -> {
            try (Connection connection = getConnection();
                 PreparedStatement statement = connection.prepareStatement("DELETE FROM ip_blocks WHERE ip_hash = ?")) {
                statement.setString(1, ipHash);
                statement.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<Void> clearAllIpBlocks() {
        return supplyAsync(() -> {
            try (Connection connection = getConnection();
                 PreparedStatement statement = connection.prepareStatement("DELETE FROM ip_blocks")) {
                statement.executeUpdate();
            }
            return null;
        });
    }
    
    public CompletableFuture<Optional<String>> getRawIpFromBlocked(String partialIp) {
        return supplyAsync(() -> {
            try (Connection connection = getConnection();
                 PreparedStatement statement = connection.prepareStatement("SELECT raw_ip FROM ip_blocks WHERE raw_ip LIKE ? LIMIT 1")) {
                statement.setString(1, "%" + partialIp + "%");
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next()) return Optional.ofNullable(resultSet.getString("raw_ip"));
                }
            }
            return Optional.empty();
        });
    }

    public CompletableFuture<Void> insertLog(UUID uuid, String action, String details, String ip) {
        long now = Instant.now().getEpochSecond();
        String ipHash = ip == null || ip.isBlank() ? null : SecurityUtils.hashIp(ip, masterKey);
        return supplyAsync(() -> {
            try (Connection connection = getConnection();
                 PreparedStatement statement = connection.prepareStatement("INSERT INTO logs (timestamp, uuid, action, details, ip_hash) VALUES (?, ?, ?, ?, ?)")) {
                statement.setLong(1, now);
                statement.setString(2, uuid == null ? null : uuid.toString());
                statement.setString(3, action);
                statement.setString(4, details);
                statement.setString(5, ipHash);
                statement.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<StatsRecord> getStats() {
        return supplyAsync(() -> {
            try (Connection connection = getConnection()) {
                int registered = count(connection, "SELECT COUNT(*) FROM players");
                int sessions = count(connection, "SELECT COUNT(*) FROM sessions WHERE expires_at > " + Instant.now().getEpochSecond());
                int locked = count(connection, "SELECT COUNT(*) FROM players WHERE is_locked = 1");
                return new StatsRecord(registered, locked, sessions);
            }
        });
    }

    public CompletableFuture<List<String>> getLockedUsernames() {
        return supplyAsync(() -> {
            List<String> list = new ArrayList<>();
            try (Connection connection = getConnection();
                 PreparedStatement statement = connection.prepareStatement("SELECT username FROM players WHERE is_locked = 1")) {
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) list.add(resultSet.getString("username"));
                }
            }
            return list;
        });
    }
    
    public CompletableFuture<List<String>> getAlts(String ip) {
        return supplyAsync(() -> {
            List<String> list = new ArrayList<>();
            try (Connection connection = getConnection();
                 PreparedStatement statement = connection.prepareStatement("SELECT username FROM players WHERE last_ip = ?")) {
                statement.setString(1, ip);
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) list.add(resultSet.getString("username"));
                }
            }
            return list;
        });
    }

    public CompletableFuture<Void> saveKnownSession(SessionRecord sessionRecord) {
        return supplyAsync(() -> {
            try (Connection connection = getConnection();
                 PreparedStatement statement = connection.prepareStatement("""
                         INSERT INTO sessions (uuid, ip_hash, expires_at)
                         VALUES (?, ?, ?)
                         ON CONFLICT(uuid) DO UPDATE SET ip_hash = excluded.ip_hash, expires_at = excluded.expires_at
                         """)) {
                statement.setString(1, sessionRecord.uuid().toString());
                statement.setString(2, sessionRecord.ipHash());
                statement.setLong(3, sessionRecord.expiresAt());
                statement.executeUpdate();
            }
            return null;
        });
    }

    public void close() {
        if (dataSource != null) dataSource.close();
    }

    private int count(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            return resultSet.next() ? resultSet.getInt(1) : 0;
        }
    }

    private PlayerRecord readPlayer(ResultSet resultSet) throws SQLException {
        String encryptedDiscord = resultSet.getString("discord_id");
        String discordId = null;
        if (encryptedDiscord != null && !encryptedDiscord.isBlank()) {
            try {
                discordId = SecurityUtils.decrypt(encryptedDiscord, masterKey);
            } catch (Exception ignored) {}
        }
        return new PlayerRecord(
                UUID.fromString(resultSet.getString("uuid")),
                resultSet.getString("username"),
                resultSet.getString("password_hash"),
                discordId,
                resultSet.getInt("is_locked") == 1,
                resultSet.getInt("password_enabled") == 1,
                InputMethod.valueOf(resultSet.getString("input_method")),
                resultSet.getInt("session_duration"),
                resultSet.getLong("registered_at"),
                resultSet.getLong("last_login"),
                resultSet.getString("last_ip")
        );
    }

    private Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    private <T> CompletableFuture<T> supplyAsync(SqlSupplier<T> supplier) {
        return ready.thenCompose(unused -> {
            CompletableFuture<T> future = new CompletableFuture<>();
            plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
                try {
                    future.complete(supplier.get());
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            });
            return future;
        });
    }

    @FunctionalInterface
    private interface SqlSupplier<T> {
        T get() throws Exception;
    }

    public record PlayerRecord(UUID uuid, String username, String passwordHash, String discordId, boolean locked, boolean passwordEnabled, InputMethod inputMethod, int sessionDuration, long registeredAt, long lastLogin, String lastIp) {
        public boolean hasPassword() { return passwordHash != null && !passwordHash.isBlank(); }
        public boolean hasDiscord() { return discordId != null && !discordId.isBlank(); }
    }
    public record SessionRecord(UUID uuid, String ipHash, long expiresAt) {}
    public record IpBlockRecord(String ipHash, long blockedUntil, int attemptCount) {}
    public record StatsRecord(int registered, int locked, int sessions) {}
}
