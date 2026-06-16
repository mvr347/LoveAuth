package me.lovelace.loveAuth.placeholder;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import me.lovelace.loveAuth.LoveAuth;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

public final class LoveAuthExpansion extends PlaceholderExpansion {
    private final LoveAuth plugin;

    public LoveAuthExpansion(LoveAuth plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "loveauth";
    }

    @Override
    public @NotNull String getAuthor() {
        return "Lovelace";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        if (player == null) return "";
        UUID uuid = player.getUniqueId();
        return switch (params) {
            case "authenticated" -> plugin.getAuthManager().isAuthenticated(uuid) ? "yes" : "no";
            case "queue_position" -> String.valueOf(plugin.getQueueManager().getPosition(uuid));
            case "queue_total" -> String.valueOf(plugin.getQueueManager().getTotal());
            default -> "";
        };
    }
}
