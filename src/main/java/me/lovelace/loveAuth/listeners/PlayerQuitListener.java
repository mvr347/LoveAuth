package me.lovelace.loveAuth.listeners;

import me.lovelace.loveAuth.LoveAuth;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.UUID;

public final class PlayerQuitListener implements Listener {
    private final LoveAuth plugin;

    public PlayerQuitListener(LoveAuth plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        plugin.getAuthManager().cleanup(uuid);
        plugin.getQueueManager().removeFromQueue(uuid);
        plugin.getChatInputHandler().cleanup(uuid);
        plugin.getSignInputHandler().cleanup(uuid);
        plugin.getLimboManager().cleanup(event.getPlayer());
        plugin.getLAdminCommand().invalidateOnlineSession(uuid);
    }
}
