package me.lovelace.loveAuth.discord;

import me.lovelace.loveAuth.LoveAuth;
import me.lovelace.loveAuth.auth.AuthManager;
import me.lovelace.loveAuth.config.ConfigManager;
import me.lovelace.loveAuth.database.DatabaseManager;
import me.lovelace.loveAuth.lang.LangManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public final class DiscordAuthManager {
    private final LoveAuth plugin;
    private final ConfigManager config;
    private final LangManager lang;
    private final DatabaseManager database;
    private final AuthManager auth;
    
    private final Map<String, UUID> pendingLinks = new ConcurrentHashMap<>();
    private final Map<UUID, String> pendingConfirmations = new ConcurrentHashMap<>();
    private final Random random = new Random();

    public DiscordAuthManager(LoveAuth plugin, ConfigManager config, LangManager lang, DatabaseManager database, AuthManager auth) {
        this.plugin = plugin;
        this.config = config;
        this.lang = lang;
        this.database = database;
        this.auth = auth;
    }

    public void startBinding(Player player) {
        if (!config.isDiscordEnabled()) {
            lang.send(player, "discord.not-enabled");
            return;
        }
        String code = generateCode(8);
        pendingLinks.put(code, player.getUniqueId());
        lang.send(player, "discord.bind-instructions", Map.of("code", code));
        
        // Timeout link code after 10 minutes
        plugin.getServer().getAsyncScheduler().runDelayed(plugin, task -> pendingLinks.remove(code), 10, TimeUnit.MINUTES);
    }

    public void sendConfirmationCode(Player player, String action) {
        String code = generateCode(6);
        pendingConfirmations.put(player.getUniqueId(), code);
        
        // TODO: Actually send message via Discord Bot API to player.getDiscordId()
        plugin.getLogger().info("[Discord] Sending confirmation code " + code + " to " + player.getName() + " for " + action);
        
        lang.send(player, "discord.confirmation-sent");
        
        plugin.getChatInputHandler().awaitInput(player, "discord.prompt-confirmation", input -> {
            if (input.equals(code)) {
                pendingConfirmations.remove(player.getUniqueId());
                handleConfirmedAction(player, action);
            } else {
                lang.send(player, "discord.wrong-code");
            }
        });
    }

    private void handleConfirmedAction(Player player, String action) {
        switch (action) {
            case "UNLINK" -> database.setDiscordId(player.getUniqueId(), null).thenRun(() -> lang.send(player, "discord.unlinked"));
            case "REMOVE_PASSWORD" -> database.setPasswordEnabled(player.getUniqueId(), false).thenRun(() -> lang.send(player, "commands.password-removed"));
            case "LOCK_ACCOUNT" -> database.setLocked(player.getUniqueId(), true).thenRun(() -> {
                lang.send(player, "commands.account-locked");
                Bukkit.getScheduler().runTask(plugin, () -> player.kick(lang.component("block.account-locked")));
            });
        }
    }

    public void handleBotLinkCommand(String code, String discordId) {
        UUID uuid = pendingLinks.remove(code);
        if (uuid != null) {
            database.setDiscordId(uuid, discordId).thenRun(() -> {
                Player player = plugin.getServer().getPlayer(uuid);
                if (player != null) {
                    lang.send(player, "discord.bind-success");
                    auth.markAuthenticated(player, true);
                }
            });
        }
    }

    public void handleBotUnlockCommand(String discordId) {
        database.findPlayerByDiscordId(discordId).thenAccept(record -> {
            if (record.isPresent()) {
                database.setLocked(record.get().uuid(), false).thenRun(() -> {
                    plugin.getLogger().info("[Discord] Account " + record.get().username() + " unlocked via Discord by " + discordId);
                });
            }
        });
    }

    private String generateCode(int length) {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) sb.append(chars.charAt(random.nextInt(chars.length())));
        return sb.toString();
    }

    public void shutdown() {}
}
