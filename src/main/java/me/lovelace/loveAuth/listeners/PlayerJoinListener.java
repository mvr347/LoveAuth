package me.lovelace.loveAuth.listeners;

import me.lovelace.loveAuth.LoveAuth;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public final class PlayerJoinListener implements Listener {
    private final LoveAuth plugin;

    public PlayerJoinListener(LoveAuth plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            // A fast reconnect or a bot scan can drop the connection within this one tick; queueing an
            // offline player would leave a ghost slot and open the queue GUI on a stale Player.
            if (!player.isOnline()) return;
            plugin.getQueueManager().addToQueue(player);
        }, 1L);
    }
}
