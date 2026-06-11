package me.lovelace.loveAuth.util;

import me.lovelace.loveAuth.LoveAuth;
import me.lovelace.loveAuth.database.DatabaseManager;
import me.lovelace.loveAuth.lang.LangManager;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

public final class LogManager {
    private final LoveAuth plugin;
    private final LangManager lang;
    private DatabaseManager databaseManager;

    public LogManager(LoveAuth plugin, LangManager lang) {
        this.plugin = plugin;
        this.lang = lang;
    }

    public void setDatabaseManager(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    public void infoKey(String key) {
        infoKey(key, Collections.emptyMap());
    }

    public void infoKey(String key, Map<String, String> placeholders) {
        plugin.getLogger().info(lang.plain(key, placeholders));
    }

    public void warnKey(String key) {
        warnKey(key, Collections.emptyMap());
    }

    public void warnKey(String key, Map<String, String> placeholders) {
        plugin.getLogger().warning(lang.plain(key, placeholders));
    }

    public void errorKey(String key, Map<String, String> placeholders, Throwable throwable) {
        String message = lang.plain(key, placeholders);
        if (throwable == null) {
            plugin.getLogger().severe(message);
            return;
        }
        plugin.getLogger().log(Level.SEVERE, message, throwable);
    }

    public void database(UUID uuid, String action, String details, String ip) {
        if (databaseManager == null) {
            return;
        }
        databaseManager.insertLog(uuid, action, details, ip).exceptionally(error -> {
            errorKey("log.database-error", Map.of("message", safeMessage(error)), error);
            return null;
        });
    }

    private String safeMessage(Throwable throwable) {
        return throwable == null || throwable.getMessage() == null ? "" : throwable.getMessage();
    }
}
