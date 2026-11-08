package me.lovelace.loveAuth.listeners;

import me.lovelace.loveAuth.LoveAuth;
import me.lovelace.loveAuth.gui.AuthMethodGui;
import me.lovelace.loveAuth.gui.DiscordGui;
import me.lovelace.loveAuth.gui.PasswordGui;
import me.lovelace.loveAuth.gui.PremiumWelcomeGui;
import me.lovelace.loveAuth.gui.RegisterGui;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.InventoryHolder;


public final class GuiCloseListener implements Listener {
    private final LoveAuth plugin;
    private final MenuOpenClock openClock = new MenuOpenClock();

    public GuiCloseListener(LoveAuth plugin) {
        this.plugin = plugin;
    }

    private static boolean isAuthGui(InventoryHolder holder) {
        return holder instanceof RegisterGui
                || holder instanceof AuthMethodGui
                || holder instanceof PasswordGui
                || holder instanceof DiscordGui;
    }

    @EventHandler
    public void onOpen(InventoryOpenEvent event) {
        if (isAuthGui(event.getInventory().getHolder())) {
            openClock.opened(event.getPlayer().getUniqueId(), System.currentTimeMillis());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        openClock.forget(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        if (event.getInventory().getHolder() instanceof PremiumWelcomeGui) {
            InventoryCloseEvent.Reason reason = event.getReason();
            if (reason == InventoryCloseEvent.Reason.PLAYER || reason == InventoryCloseEvent.Reason.UNKNOWN) {
                if (!plugin.getAuthManager().isAuthenticated(player.getUniqueId())) {
                    plugin.getAuthManager().completeNewAccountAuth(player);
                    plugin.getLangManager().send(player, "auth.premium-skip-reminder");
                }
            }
            return;
        }

        if (plugin.getAuthManager().isAuthenticated(player.getUniqueId())) return;

        InventoryHolder holder = event.getInventory().getHolder();
        if (!isAuthGui(holder)) return;

        InventoryCloseEvent.Reason reason = event.getReason();
        long sinceOpen = openClock.sinceOpen(player.getUniqueId(), System.currentTimeMillis());
        // The world-loading screen after a cross-world limbo teleport closes the menu client-side;
        // that must reopen it, not kick a player who never touched anything.
        switch (AuthCloseDecision.decide(
                reason == InventoryCloseEvent.Reason.PLAYER,
                reason == InventoryCloseEvent.Reason.UNKNOWN,
                sinceOpen, plugin.getConfigManager().getCloseGraceMs())) {
            case KICK -> player.kick(plugin.getLangManager().component("kick.closed-gui"));
            case REOPEN -> {
                openClock.reopening(player.getUniqueId(), System.currentTimeMillis());
                org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline() || plugin.getAuthManager().isAuthenticated(player.getUniqueId())) return;
                // Something else (another auth menu, chat input) may already have taken over.
                if (isAuthGui(player.getOpenInventory().getTopInventory().getHolder())) return;
                if (plugin.getChatInputHandler() != null && plugin.getChatInputHandler().isAwaiting(player)) return;
                plugin.getGuiManager().reopenAuthMenu(player, holder);
                });
            }
            case IGNORE -> { }
        }
    }
}
