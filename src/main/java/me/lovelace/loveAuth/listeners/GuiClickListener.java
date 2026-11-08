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
            var zone = confirmGui.actionAt(event.getRawSlot()).orElse(null);
            if (zone == AuthMenuLayout.Zone.CONFIRM) confirmGui.handleConfirm();
            else if (zone == AuthMenuLayout.Zone.CANCEL) confirmGui.handleCancel();
            return;
        }

        if (holder instanceof AccountGui accountGui) {
            event.setCancelled(true);
            SoundUtils.click(player);
            AuthManager auth = plugin.getAuthManager();
            AccountGui.Action action = accountGui.actionAt(event.getRawSlot()).orElse(null);
            if (action == null) return;

            if (action == AccountGui.Action.PASSWORD) {
                ClickType passwordClick = event.getClick();
                if (!passwordClick.isLeftClick() && !passwordClick.isRightClick()) return;
                boolean delete = passwordClick.isRightClick();
                plugin.getDatabaseManager().findPlayer(player.getUniqueId()).thenAccept(record -> Bukkit.getScheduler().runTask(plugin, () -> {
                    boolean hasPassword = record.map(r -> r.hasPassword() && r.passwordEnabled()).orElse(false);
                    boolean hasDiscord = record.map(r -> r.hasDiscord()).orElse(false);
                    if (!delete) {
                        plugin.getGuiManager().openConfirm(player, "gui.confirm.change-password",
                            () -> auth.requestPasswordChange(player),
                            () -> accountGui.open());
                    } else if (!hasPassword) {
                        plugin.getLangManager().send(player, "block.no-password-to-delete");
                    } else if (!AccountGui.canDeletePassword(true, hasDiscord, plugin.getConfigManager().isDiscordEnabled(), auth.isPremium(player))) {
                        plugin.getLangManager().send(player, "block.password-required");
                    } else {
                        // With a bound Discord the removal is confirmed there as before; a premium account
                        // without one is confirmed by the menu alone and falls back to the premium login.
                        boolean viaDiscord = hasDiscord && plugin.getConfigManager().isDiscordEnabled();
                        plugin.getGuiManager().openConfirm(player, "gui.confirm.delete-password",
                            () -> {
                                if (viaDiscord) {
                                    plugin.getDiscordAuthManager().sendConfirmation(player, "REMOVE_PASSWORD");
                                } else {
                                    plugin.getDatabaseManager().setPasswordEnabled(player.getUniqueId(), false)
                                        .thenRun(() -> Bukkit.getScheduler().runTask(plugin, () -> {
                                            plugin.getLangManager().send(player, "commands.password-removed-premium");
                                            accountGui.open();
                                        }));
                                }
                            },
                            () -> accountGui.open());
                    }
                }));
            } else if (action == AccountGui.Action.SESSION) {
                ClickType click = event.getClick();
                if (!click.isLeftClick() && !click.isRightClick() && click != ClickType.MIDDLE) return;
                plugin.getDatabaseManager().findPlayer(player.getUniqueId()).thenAccept(record -> {
                    if (!record.map(r -> r.hasPassword() && r.passwordEnabled()).orElse(false)) return;
                    int current = record.map(r -> r.sessionDuration()).orElse(7);
                    int next;
                    if (click == ClickType.MIDDLE) {
                        next = 0;
                    } else if (click.isLeftClick()) {
                        next = current + 1;
                        if (next > AccountGui.MAX_SESSION_DAYS) next = 0;
                    } else {
                        next = current - 1;
                        if (next < 0) next = AccountGui.MAX_SESSION_DAYS;
                    }
                    final int finalNext = next;
                    plugin.getDatabaseManager().setSessionDuration(player.getUniqueId(), finalNext).thenRun(() -> {
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            accountGui.refresh();
                            if (finalNext == 0 && current != 0) {
                                auth.getSessionManager().invalidate(player.getUniqueId());
                            }
                        });
                    });
                });
            } else if (action == AccountGui.Action.LOGIN_METHOD) {
                auth.switchAuthMethod(player).thenRun(() -> Bukkit.getScheduler().runTask(plugin, accountGui::refresh));
            } else if (action == AccountGui.Action.DISCORD) {
                if (!plugin.getConfigManager().isDiscordEnabled()) return;
                // No extra mini window: bind straight away, or unlink after the confirm menu.
                plugin.getDatabaseManager().findPlayer(player.getUniqueId()).thenAccept(record -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!record.map(r -> r.hasDiscord()).orElse(false)) {
                        plugin.getDiscordAuthManager().startBinding(player);
                    } else if (!record.map(r -> r.hasPassword() && r.passwordEnabled()).orElse(false)) {
                        plugin.getLangManager().send(player, "block.discord-unlink-requires-password");
                    } else {
                        plugin.getGuiManager().openConfirm(player, "gui.confirm.unlink-discord",
                            () -> plugin.getDiscordAuthManager().sendConfirmation(player, "UNLINK"),
                            () -> accountGui.open());
                    }
                }));
            } else if (action == AccountGui.Action.LOGOUT) {
                plugin.getGuiManager().openConfirm(player, "gui.confirm.logout",
                    () -> auth.forceLogout(player.getUniqueId()).thenRun(() -> Bukkit.getScheduler().runTask(plugin, () -> player.kick(plugin.getLangManager().component("commands.session-reset")))),
                    () -> accountGui.open());
            } else if (action == AccountGui.Action.CLOSE) {
                player.closeInventory();
            } else if (action == AccountGui.Action.BACK) {
                player.closeInventory();
                accountGui.goBack();
            }
            return;
        }

        if (holder instanceof DiscordGui discordGui) {
            event.setCancelled(true);
            SoundUtils.click(player);
            DiscordGui.Action action = discordGui.actionAt(event.getRawSlot()).orElse(null);
            if (action == null) return;
            if (action == DiscordGui.Action.CLOSE) player.closeInventory();
            else if (action == DiscordGui.Action.BACK) plugin.getGuiManager().reopenAccount(player);
            else if (action == DiscordGui.Action.BIND_OR_UNLINK) {
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
            } else if (action == DiscordGui.Action.TOGGLE_PASSWORD) {
                plugin.getDatabaseManager().findPlayer(player.getUniqueId()).thenAccept(record -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (record.map(r -> r.hasDiscord()).orElse(false)) {
                        boolean next = !record.get().passwordEnabled();
                        plugin.getDatabaseManager().setPasswordEnabled(player.getUniqueId(), next).thenRun(() -> Bukkit.getScheduler().runTask(plugin, discordGui::refresh));
                    } else {
                        plugin.getLangManager().send(player, "block.password-required");
                    }
                }));
            }
            return;
        }

        if (holder instanceof PasswordGui) {
            event.setCancelled(true);
            SoundUtils.click(player);
            int slot = event.getRawSlot();
            if (slot == 25) plugin.getGuiManager().openAuthMethod(player);
            else if (slot == 13) plugin.getAuthManager().requestPasswordLogin(player);
            else if (slot == 26) closeAuthGate(player);
            return;
        }

        InventoryView view = event.getView();
        Component title = view.title();
        LangManager lang = plugin.getLangManager();
        AuthManager auth = plugin.getAuthManager();

        if (holder instanceof AuthMethodGui methodGui) {
            event.setCancelled(true);
            SoundUtils.click(player);
            AuthMethodGui.Action action = methodGui.actionAt(event.getRawSlot()).orElse(null);
            if (action == AuthMethodGui.Action.PASSWORD) {
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
            else if (action == AuthMethodGui.Action.DISCORD) {
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
            else if (action == AuthMethodGui.Action.CLOSE) closeAuthGate(player);
            return;
        }

        if (holder instanceof RegisterGui registerGui) {
            event.setCancelled(true);
            SoundUtils.click(player);
            int slot = event.getRawSlot();
            boolean discordEnabled = plugin.getConfigManager().isDiscordEnabled();
            if (registerGui.zoned()) {
                var zone = registerGui.zoneAt(slot).orElse(null);
                if (zone == AuthMenuLayout.Zone.PASSWORD) auth.requestRegistration(player);
                else if (zone == AuthMenuLayout.Zone.DISCORD && discordEnabled) plugin.getDiscordAuthManager().startBinding(player);
                return;
            }
            int[] slots = RegisterGui.slots(discordEnabled);
            if (slot == slots[0]) auth.requestRegistration(player);
            else if (discordEnabled && slot == slots[1]) plugin.getDiscordAuthManager().startBinding(player);
            else if (slot == 26) closeAuthGate(player);
        } else if (holder instanceof PremiumWelcomeGui premiumGui) {
            event.setCancelled(true);
            SoundUtils.click(player);
            int slot = event.getRawSlot();
            boolean discordEnabled = plugin.getConfigManager().isDiscordEnabled();
            if (premiumGui.zoned()) {
                var zone = premiumGui.zoneAt(slot).orElse(null);
                if (zone == AuthMenuLayout.Zone.PASSWORD) auth.requestRegistration(player);
                else if (zone == AuthMenuLayout.Zone.DISCORD && discordEnabled) plugin.getDiscordAuthManager().startBinding(player);
                else if (zone == AuthMenuLayout.Zone.SKIP) auth.completeNewAccountAuth(player);
                return;
            }
            int[] slots = PremiumWelcomeGui.slots(discordEnabled);
            if (slot == slots[0]) auth.requestRegistration(player);
            else if (discordEnabled && slot == slots[1]) plugin.getDiscordAuthManager().startBinding(player);
            else if (slot == slots[slots.length - 1]) auth.completeNewAccountAuth(player);
            else if (slot == 26) {
                if (!auth.isAuthenticated(player.getUniqueId())) {
                    auth.completeNewAccountAuth(player);
                    lang.send(player, "auth.premium-skip-reminder");
                }
                player.closeInventory();
            }
        } else if (holder instanceof QueueGui queueGui) {
            event.setCancelled(true);
            SoundUtils.click(player);
            QueueGui.Action action = queueGui.actionAt(event.getRawSlot()).orElse(null);
            if (action == QueueGui.Action.REFRESH) plugin.getGuiManager().openQueue(player);
            else if (action == QueueGui.Action.CLOSE) player.closeInventory();
        }
    }

    /**
     * Закрывает GUI обязательного этапа авторизации: если игрок ещё не вошёл, поведение
     * должно совпадать с закрытием крестиком/Esc (GuiCloseListener) — кик, а не тихий выход.
     */
    private void closeAuthGate(Player player) {
        if (!plugin.getAuthManager().isAuthenticated(player.getUniqueId())) {
            player.kick(plugin.getLangManager().component("kick.closed-gui"));
        } else {
            player.closeInventory();
        }
    }

    private boolean isGui(Component title, String key) {
        String plainTitle = PlainTextComponentSerializer.plainText().serialize(title);
        String expected = plugin.getLangManager().plain(key);
        return plainTitle.equals(expected);
    }
}
