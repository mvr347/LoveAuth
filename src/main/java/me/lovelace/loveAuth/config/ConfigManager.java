package me.lovelace.loveAuth.config;

import me.lovelace.loveAuth.LoveAuth;
import me.lovelace.loveAuth.input.InputMethod;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.List;

public final class ConfigManager {
    private final LoveAuth plugin;
    private final List<String> warningKeys = new ArrayList<>();
    private FileConfiguration config;
    private boolean generatedSecurity;

    public ConfigManager(LoveAuth plugin) {
        this.plugin = plugin;
    }

    public void load() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        config = plugin.getConfig();
        warningKeys.clear();
        validate();
    }

    public void reload() {
        load();
    }

    private void validate() {
        if (getBcryptStrength() < 10) {
            warningKeys.add("log.config-warn-bcrypt");
        }
        int duration = config.getInt("session.duration-days", 7);
        if (duration != 0 && (duration < 1 || duration > 28)) {
            config.set("session.duration-days", 7);
            plugin.saveConfig();
            warningKeys.add("log.config-warn-session");
        }
        try {
            InputMethod.valueOf(config.getString("default-input-method", "CHAT").toUpperCase());
        } catch (IllegalArgumentException ignored) {
            config.set("default-input-method", "CHAT");
            plugin.saveConfig();
        }
    }

    public List<String> consumeWarningKeys() {
        List<String> copy = List.copyOf(warningKeys);
        warningKeys.clear();
        return copy;
    }

    public boolean consumeGeneratedSecurityFlag() {
        boolean value = generatedSecurity;
        generatedSecurity = false;
        return value;
    }

    public void markGeneratedSecurity() {
        generatedSecurity = true;
    }

    public String getDatabaseFile() { return config.getString("database.file", "loveauth.db"); }
    public String getPepper() { return config.getString("security.pepper", ""); }
    public void setPepper(String pepper) {
        config.set("security.pepper", pepper);
        markGeneratedSecurity();
        plugin.saveConfig();
    }
    public String getMasterKey() { return config.getString("security.master-key", ""); }
    public void setMasterKey(String masterKey) {
        config.set("security.master-key", masterKey);
        markGeneratedSecurity();
        plugin.saveConfig();
    }
    public int getBcryptStrength() { return config.getInt("security.bcrypt-strength", 12); }
    public boolean isSessionEnabled() { return config.getBoolean("session.enabled", true) && getSessionDurationDays() > 0; }
    public int getSessionDurationDays() { return config.getInt("session.duration-days", 7); }
    public boolean isSessionBindToIp() { return config.getBoolean("session.bind-to-ip", true); }
    public int getAuthTimeoutSeconds() { return config.getInt("auth.timeout-seconds", 90); }
    public int getMaxAttempts() { return config.getInt("auth.max-attempts", 3); }
    public int getLockoutDurationMinutes() { return config.getInt("auth.lockout-duration-minutes", 60); }
    public boolean isQueueEnabled() { return config.getBoolean("queue.enabled", true); }
    public int getQueueMaxPlayers() { return config.getInt("queue.max-players", 100); }
    public boolean isLimboEnabled() { return config.getBoolean("limbo.enabled", true); }
    public String getLimboWorldName() { return config.getString("limbo.world-name", "loveauth_limbo"); }
    public InputMethod getDefaultInputMethod() {
        try {
            return InputMethod.valueOf(config.getString("default-input-method", "CHAT").toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return InputMethod.CHAT;
        }
    }
    public boolean isDiscordEnabled() { return config.getBoolean("discord.enabled", false); }
    public String getDiscordBotToken() { return config.getString("discord.bot-token", ""); }
    public String getDiscordGuildId() { return config.getString("discord.guild-id", ""); }
    public String getLanguage() { return config.getString("language", "lang"); }
    public boolean isPremiumSkipEnabled() { return config.getBoolean("auth.premium-skip", true); }
    public List<String> getDiscordAdminIds() { return config.getStringList("discord.admin-ids"); }
}
