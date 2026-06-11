package me.lovelace.loveAuth.listeners;

import me.lovelace.loveAuth.LoveAuth;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

public final class PlayerQuitListener implements Listener {
    private final LoveAuth plugin;

    public PlayerQuitListener(LoveAuth plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.getAuthManager().cleanup(event.getPlayer().getUniqueId());
        plugin.getQueueManager().removeFromQueue(event.getPlayer().getUniqueId());
        plugin.getChatInputHandler().cleanup(event.getPlayer().getUniqueId());
        plugin.getAnvilInputHandler().cleanup(event.getPlayer().getUniqueId());
    }
}
