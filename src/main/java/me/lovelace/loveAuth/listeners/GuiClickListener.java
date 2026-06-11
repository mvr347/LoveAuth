package me.lovelace.loveAuth.listeners;

import me.lovelace.loveAuth.LoveAuth;
import me.lovelace.loveAuth.auth.AuthManager;
import me.lovelace.loveAuth.gui.AccountGui;
import me.lovelace.loveAuth.gui.ConfirmGui;
import me.lovelace.loveAuth.gui.SessionGui;
import me.lovelace.loveAuth.lang.LangManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;

import java.util.Map;

public final class GuiClickListener implements Listener {
    private final LoveAuth plugin;

    public GuiClickListener(LoveAuth plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        InventoryHolder holder = event.getInventory().getHolder();
        
        if (holder instanceof ConfirmGui confirmGui) {
            event.setCancelled(true);
            if (event.getRawSlot() == 12) confirmGui.handleConfirm();
            else if (event.getRawSlot() == 14) confirmGui.handleCancel();
            return;
        }

        if (holder instanceof AccountGui accountGui) {
            event.setCancelled(true);
            AuthManager auth = plugin.getAuthManager();
            int slot = event.getRawSlot();

            if (slot == 20 || slot == 19) {
                plugin.getGuiManager().openConfirm(player, "gui.confirm.change-password", 
                    () -> auth.requestPasswordChange(player), 
                    () -> accountGui.open());
            } else if (slot == 21) {
                plugin.getDatabaseManager().findPlayer(player.getUniqueId()).thenAccept(record -> {
                    if (record.map(r -> r.hasDiscord()).orElse(false)) {
                        plugin.getDiscordAuthManager().sendConfirmationCode(player, "REMOVE_PASSWORD");
                    } else {
                        plugin.getLangManager().send(player, "gui.account.delete-password-locked");
                    }
                });
            } else if (slot == 22) {
                new SessionGui(player, plugin.getLangManager(), auth).open();
            } else if (slot == 24) {
                plugin.getDatabaseManager().findPlayer(player.getUniqueId()).thenAccept(record -> {
                    if (record.map(r -> r.hasDiscord()).orElse(false)) {
                        plugin.getDiscordAuthManager().sendConfirmationCode(player, "UNLINK");
                    } else {
                        plugin.getDiscordAuthManager().startBinding(player);
                    }
                });
            } else if (slot == 25) {
                plugin.getDatabaseManager().findPlayer(player.getUniqueId()).thenAccept(record -> {
                    if (record.map(r -> r.hasDiscord()).orElse(false)) {
                        plugin.getGuiManager().openConfirm(player, "gui.confirm.lock-account", 
                            () -> plugin.getDiscordAuthManager().sendConfirmationCode(player, "LOCK_ACCOUNT"), 
                            () -> accountGui.open());
                    }
                });
            } else if (slot == 31) {
                plugin.getGuiManager().openConfirm(player, "gui.confirm.logout", 
                    () -> auth.forceLogout(player.getUniqueId()).thenRun(() -> Bukkit.getScheduler().runTask(plugin, () -> player.kick(plugin.getLangManager().component("commands.session-reset")))), 
                    () -> accountGui.open());
            }
            return;
        }

        if (holder instanceof SessionGui sessionGui) {
            event.setCancelled(true);
            int slot = event.getRawSlot();
            int[] daysMap = {0, 1, 3, 7, 14, 28};
            int[] slotsMap = {10, 11, 12, 13, 14, 15};

            for (int i = 0; i < slotsMap.length; i++) {
                if (slot == slotsMap[i]) {
                    int days = daysMap[i];
                    plugin.getDatabaseManager().setSessionDuration(player.getUniqueId(), days).thenRun(() -> {
                        plugin.getAuthManager().getSessionManager().invalidate(player.getUniqueId()).thenRun(() -> {
                            Bukkit.getScheduler().runTask(plugin, () -> player.kick(plugin.getLangManager().component("commands.session-reset")));
                        });
                    });
                    return;
                }
            }
            if (slot == 22) new AccountGui(player, plugin.getLangManager(), plugin.getAuthManager()).open();
            return;
        }

        InventoryView view = event.getView();
        Component title = view.title();
        LangManager lang = plugin.getLangManager();
        AuthManager auth = plugin.getAuthManager();

        if (isGui(title, "gui.auth-method.title")) {
            event.setCancelled(true);
            if (event.getRawSlot() == 12) {
                if (auth.isRegisteredCached(player.getUniqueId())) auth.requestPasswordLogin(player);
                else auth.requestRegistration(player);
            }
            else if (event.getRawSlot() == 14) plugin.getGuiManager().openDiscord(player);
        } else if (isGui(title, "gui.password.title")) {
            event.setCancelled(true);
            if (event.getRawSlot() == 11) plugin.getGuiManager().openAuthMethod(player);
            else if (event.getRawSlot() == 13) auth.requestPasswordLogin(player);
        } else if (isGui(title, "gui.register.title")) {
            event.setCancelled(true);
            if (event.getRawSlot() == 13) auth.requestRegistration(player);
        } else if (isGui(title, "gui.premium-welcome.title")) {
            event.setCancelled(true);
            if (event.getRawSlot() == 11) auth.requestRegistration(player);
            else if (event.getRawSlot() == 13) plugin.getDiscordAuthManager().startBinding(player);
            else if (event.getRawSlot() == 15) auth.markAuthenticated(player, true);
        } else if (isGui(title, "gui.queue.title")) {
            event.setCancelled(true);
            if (event.getRawSlot() == 22) plugin.getGuiManager().openQueue(player);
        }
    }

    private boolean isGui(Component title, String key) {
        String plainTitle = PlainTextComponentSerializer.plainText().serialize(title);
        String expected = plugin.getLangManager().plain(key);
        return plainTitle.equals(expected);
    }
}
