package me.lovelace.loveAuth.listeners;

import me.lovelace.loveAuth.LoveAuth;
import me.lovelace.loveAuth.gui.PremiumWelcomeGui;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;

public final class GuiCloseListener implements Listener {
    private final LoveAuth plugin;

    public GuiCloseListener(LoveAuth plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        if (event.getInventory().getHolder() instanceof PremiumWelcomeGui) {
            InventoryCloseEvent.Reason reason = event.getReason();
            if (reason == InventoryCloseEvent.Reason.PLAYER || reason == InventoryCloseEvent.Reason.UNKNOWN) {
                if (!plugin.getAuthManager().isAuthenticated(player.getUniqueId())) {
                    plugin.getAuthManager().markAuthenticated(player, true);
                    plugin.getLangManager().send(player, "auth.premium-skip-reminder");
                }
            }
            return;
        }

        if (plugin.getAuthManager().isAuthenticated(player.getUniqueId())) return;

        // Kick logic for other GUIs if needed (usually handled by task timeout, but can be forced here)
    }
}
