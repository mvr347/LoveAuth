package me.lovelace.loveAuth.listeners;

import me.lovelace.loveAuth.LoveAuth;
import me.lovelace.loveAuth.auth.AuthManager;
import me.lovelace.loveAuth.gui.*;
import me.lovelace.loveAuth.lang.LangManager;
import me.lovelace.loveAuth.util.SoundUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
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
            SoundUtils.click(player);
            if (event.getRawSlot() == ConfirmGui.CONFIRM_SLOT) confirmGui.handleConfirm();
            else if (event.getRawSlot() == ConfirmGui.CANCEL_SLOT) confirmGui.handleCancel();
            return;
        }

        if (holder instanceof AccountGui accountGui) {
            event.setCancelled(true);
            SoundUtils.click(player);
            AuthManager auth = plugin.getAuthManager();
            int slot = event.getRawSlot();

            if (slot == 10) {
                plugin.getGuiManager().openConfirm(player, "gui.confirm.change-password",
                    () -> auth.requestPasswordChange(player),
                    () -> accountGui.open());
            } else if (slot == 11) {
                plugin.getDatabaseManager().findPlayer(player.getUniqueId()).thenAccept(record -> {
                    if (record.map(r -> r.hasDiscord()).orElse(false)) {
                        plugin.getDiscordAuthManager().sendConfirmation(player, "REMOVE_PASSWORD");
                    } else {
                        plugin.getLangManager().send(player, "gui.account.delete-password-locked");
                    }
                });
            } else if (slot == 12) {
                new SessionGui(player, plugin.getLangManager(), auth).open();
            } else if (slot == 13) {
                auth.switchInputMethod(player).thenRun(() -> Bukkit.getScheduler().runTask(plugin, accountGui::refresh));
            } else if (slot == 14) {
                new DiscordGui(player, plugin.getLangManager(), plugin.getConfigManager(), plugin.getDiscordAuthManager(), auth).open();
            } else if (slot == 16) {
                plugin.getGuiManager().openConfirm(player, "gui.confirm.logout",
                    () -> auth.forceLogout(player.getUniqueId()).thenRun(() -> Bukkit.getScheduler().runTask(plugin, () -> player.kick(plugin.getLangManager().component("commands.session-reset")))),
                    () -> accountGui.open());
            } else if (slot == AccountGui.CLOSE_SLOT) {
                player.closeInventory();
            } else if (slot == AccountGui.BACK_SLOT) {
                player.closeInventory();
                accountGui.goBack();
            }
            return;
        }

        if (holder instanceof DiscordGui discordGui) {
            event.setCancelled(true);
            SoundUtils.click(player);
            int slot = event.getRawSlot();
            if (slot == 26) player.closeInventory();
            else if (slot == 25) new AccountGui(player, plugin.getLangManager(), plugin.getAuthManager()).open();
            else if (slot == 13) {
                plugin.getDatabaseManager().findPlayer(player.getUniqueId()).thenAccept(record -> Bukkit.getScheduler().runTask(plugin, () -> {
                    boolean hasDiscord = record.map(r -> r.hasDiscord()).orElse(false);
                    if (!hasDiscord) {
                        plugin.getDiscordAuthManager().startBinding(player);
                    } else if (!record.map(r -> r.hasPassword() && r.passwordEnabled()).orElse(false)) {
                        plugin.getLangManager().send(player, "block.discord-unlink-requires-password");
                    } else {
                        plugin.getDiscordAuthManager().sendConfirmation(player, "UNLINK");
                    }
                }));
            } else if (slot == 15) {
                plugin.getDatabaseManager().findPlayer(player.getUniqueId()).thenAccept(record -> {
                    if (record.map(r -> r.hasDiscord()).orElse(false)) {
                        boolean next = !record.get().passwordEnabled();
                        plugin.getDatabaseManager().setPasswordEnabled(player.getUniqueId(), next).thenRun(() -> Bukkit.getScheduler().runTask(plugin, discordGui::refresh));
                    } else {
                        plugin.getLangManager().send(player, "block.password-required");
                    }
                });
            }
            return;
        }

        if (holder instanceof PasswordGui) {
            event.setCancelled(true);
            SoundUtils.click(player);
            if (event.getRawSlot() == 11) plugin.getGuiManager().openAuthMethod(player);
            else if (event.getRawSlot() == 13) plugin.getAuthManager().requestPasswordLogin(player);
            return;
        }

        if (holder instanceof AdminGui adminGui) {
            event.setCancelled(true);
            if (!player.hasPermission("loveauth.admin")) return;
            SoundUtils.click(player);
            int slot = event.getRawSlot();

            switch (slot) {
                case 20 -> {
                    plugin.getDatabaseManager().getLockedUsernames().thenAccept(list -> {
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            if (list.isEmpty()) {
                                plugin.getLangManager().send(player, "gui.admin.no-locked");
                            } else {
                                plugin.getLangManager().send(player, "gui.admin.locked-list-header");
                                list.forEach(name -> player.sendMessage(
                                    plugin.getLangManager().component("gui.admin.locked-list-entry",
                                        Map.of("player", name))));
                            }
                        });
                    });
                }
                case 22 -> {
                    player.closeInventory();
                    plugin.getChatInputHandler().awaitInput(player, "gui.admin.unlock-prompt", name ->
                        plugin.getDatabaseManager().findPlayerByName(name).thenAccept(record -> {
                            if (record.isEmpty()) {
                                plugin.getLangManager().send(player, "general.player-not-found", Map.of("player", name));
                            } else {
                                plugin.getBruteForceProtection().unlockAccount(record.get().uuid())
                                    .thenRun(() -> plugin.getLangManager().send(player,
                                        "commands.admin-unlock", Map.of("player", name)));
                            }
                        })
                    );
                }
                case 24 -> {
                    adminGui.refresh();
                }
                case 31 -> {
                    player.closeInventory();
                    plugin.getChatInputHandler().awaitInput(player, "gui.admin.session-reset-prompt", name ->
                        plugin.getDatabaseManager().findPlayerByName(name).thenAccept(record -> {
                            if (record.isEmpty()) {
                                plugin.getLangManager().send(player, "general.player-not-found", Map.of("player", name));
                            } else {
                                plugin.getSessionManager().invalidate(record.get().uuid())
                                    .thenRun(() -> plugin.getLangManager().send(player,
                                        "commands.session-reset-for", Map.of("player", name)));
                            }
                        })
                    );
                }
                case 32 -> {
                    plugin.getDatabaseManager().clearAllIpBlocks().thenRun(() -> {
                        plugin.getLangManager().send(player, "commands.ip-blocks-cleared");
                        Bukkit.getScheduler().runTask(plugin, adminGui::refresh);
                    });
                }
                case 53 -> player.closeInventory();
            }
            return;
        }

        if (holder instanceof SessionGui sessionGui) {
            event.setCancelled(true);
            SoundUtils.click(player);
            int slot = event.getRawSlot();
            if (slot == 26) {
                player.closeInventory();
                return;
            }
            if (slot == 25) {
                new AccountGui(player, plugin.getLangManager(), plugin.getAuthManager()).open();
                return;
            }
            if (slot == 13) {
                ClickType click = event.getClick();
                plugin.getDatabaseManager().findPlayer(player.getUniqueId()).thenAccept(record -> {
                    int current = record.map(r -> r.sessionDuration()).orElse(7);
                    int next;
                    if (click.isLeftClick()) {
                        next = current + 1;
                        if (next > 28) next = 0;
                    } else if (click.isRightClick()) {
                        next = current - 1;
                        if (next < 0) next = 28;
                    } else return;
                    
                    final int finalNext = next;
                    plugin.getDatabaseManager().setSessionDuration(player.getUniqueId(), finalNext).thenRun(() -> {
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            sessionGui.refresh();
                            if (finalNext == 0 && current != 0) {
                                plugin.getAuthManager().getSessionManager().invalidate(player.getUniqueId());
                            }
                        });
                    });
                });
            }
            return;
        }

        InventoryView view = event.getView();
        Component title = view.title();
        LangManager lang = plugin.getLangManager();
        AuthManager auth = plugin.getAuthManager();

        if (isGui(title, "gui.auth-method.title")) {
            event.setCancelled(true);
            SoundUtils.click(player);
            if (event.getRawSlot() == 12) {
                if (!auth.isRegisteredCached(player.getUniqueId())) {
                    auth.requestRegistration(player);
                } else {
                    plugin.getDatabaseManager().findPlayer(player.getUniqueId()).thenAccept(record -> {
                        boolean hasPassword = record.map(r -> r.hasPassword() && r.passwordEnabled()).orElse(false);
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            if (hasPassword) auth.requestPasswordLogin(player);
                            else lang.send(player, "block.password-login-disabled");
                        });
                    });
                }
            }
            else if (event.getRawSlot() == 14) {
                if (!plugin.getConfigManager().isDiscordEnabled()) return;
                plugin.getDatabaseManager().findPlayer(player.getUniqueId()).thenAccept(record -> Bukkit.getScheduler().runTask(plugin, () -> {
                    boolean hasDiscord = record.map(r -> r.hasDiscord()).orElse(false);
                    if (hasDiscord) {
                        player.closeInventory();
                        plugin.getDiscordAuthManager().requestDiscordLogin(player).thenAccept(sent -> Bukkit.getScheduler().runTask(plugin, () -> {
                            if (!sent && player.isOnline()) plugin.getGuiManager().openAuthMethod(player);
                        }));
                    } else {
                        plugin.getDiscordAuthManager().startBinding(player);
                    }
                }));
            }
        } else if (isGui(title, "gui.register.title")) {
            event.setCancelled(true);
            SoundUtils.click(player);
            int slot = event.getRawSlot();
            if (slot == 11) auth.requestRegistration(player);
            else if (slot == 15 && plugin.getConfigManager().isDiscordEnabled()) plugin.getDiscordAuthManager().startBinding(player);
        } else if (isGui(title, "gui.premium-welcome.title")) {
            event.setCancelled(true);
            SoundUtils.click(player);
            if (event.getRawSlot() == 11) auth.requestRegistration(player);
            else if (event.getRawSlot() == 13) plugin.getDiscordAuthManager().startBinding(player);
            else if (event.getRawSlot() == 15) auth.markAuthenticated(player, true);
        } else if (isGui(title, "gui.queue.title")) {
            event.setCancelled(true);
            SoundUtils.click(player);
            if (event.getRawSlot() == 22) plugin.getGuiManager().openQueue(player);
        }
    }

    private boolean isGui(Component title, String key) {
        String plainTitle = PlainTextComponentSerializer.plainText().serialize(title);
        String expected = plugin.getLangManager().plain(key);
        return plainTitle.equals(expected);
    }
}
