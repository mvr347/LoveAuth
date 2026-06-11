package me.lovelace.loveAuth.listeners;

import me.lovelace.loveAuth.LoveAuth;
import me.lovelace.loveAuth.gui.LoveAuthHolder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;

public final class GuiCloseListener implements Listener {
    private final LoveAuth plugin;

    public GuiCloseListener(LoveAuth plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        if (!player.isOnline()) return;
        if (plugin.getAuthManager().isAuthenticated(player.getUniqueId())) return;
        if (!(event.getInventory().getHolder() instanceof LoveAuthHolder)) return;

        InventoryCloseEvent.Reason reason = event.getReason();

        if (reason == InventoryCloseEvent.Reason.PLUGIN || 
            reason == InventoryCloseEvent.Reason.TELEPORT ||
            reason == InventoryCloseEvent.Reason.DEATH || 
            reason == InventoryCloseEvent.Reason.OPEN_NEW) {
            return;
        }

        // ✅ ИСПРАВИТЬ — проверять UUID версию игрока (как в AuthManager.isPremium)
        if (player.getUniqueId().version() == 4) {
             plugin.getAuthManager().markAuthenticated(player, true);
             return;
        }

        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline() && !plugin.getAuthManager().isAuthenticated(player.getUniqueId())) {
                player.kick(plugin.getLangManager().component("kick.closed-gui"));
            }
        });
    }
}
