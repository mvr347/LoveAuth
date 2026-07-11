package me.lovelace.loveAuth.commands;

import me.lovelace.loveAuth.LoveAuth;
import me.lovelace.loveAuth.database.DatabaseManager;
import me.lovelace.loveAuth.lang.LangManager;
import me.lovelace.loveAuth.security.SecurityUtils;
import me.lovelace.loveAuth.util.SoundUtils;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class LAdminCommand implements CommandExecutor, TabCompleter {
    private static final long ADMIN_SESSION_DURATION_MILLIS = 3_600_000L;
    /** Sentinel expiry value meaning "valid for as long as the player stays online". */
    private static final long NO_EXPIRY = -1L;

    private final LoveAuth plugin;
    private final Map<UUID, Long> adminSessions = new ConcurrentHashMap<>();

    public LAdminCommand(LoveAuth plugin) {
        this.plugin = plugin;
    }

    /** In-game admin-password approval: valid until the player logs out, no fixed expiry. */
    public void recordAdminSession(UUID uuid) {
        adminSessions.put(uuid, NO_EXPIRY);
    }

    /** Discord-button-approved admin actions: expire after a fixed duration regardless of online state. */
    public void recordTimedAdminSession(UUID uuid) {
        adminSessions.put(uuid, System.currentTimeMillis() + ADMIN_SESSION_DURATION_MILLIS);
    }

    /** Invalidates only "valid while online" sessions; timed Discord-approved sessions survive logout. */
    public void invalidateOnlineSession(UUID uuid) {
        adminSessions.computeIfPresent(uuid, (u, expiry) -> expiry == NO_EXPIRY ? null : expiry);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("loveauth.admin")) {
            plugin.getLangManager().send(sender, "general.no-permission");
            return true;
        }

        if (!(sender instanceof Player player)) {
            handleCommand(sender, args);
            return true;
        }

        Long sessionExpiry = adminSessions.get(player.getUniqueId());
        if (sessionExpiry != null && (sessionExpiry == NO_EXPIRY || sessionExpiry > System.currentTimeMillis())) {
            handleCommand(player, args);
            return true;
        }

        plugin.getDatabaseManager().getAdminPassword(player.getUniqueId()).thenAccept(opt -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (opt.isEmpty()) {
                boolean settingPassword = args.length > 0 && (args[0].equalsIgnoreCase("setadminpass") || args[0].equalsIgnoreCase("установитьадминпароль"));
                if (settingPassword) {
                    handleCommand(player, args);
                } else {
                    plugin.getLangManager().send(player, "gui.admin.admin-password-not-set");
                }
                return;
            }
            plugin.getDatabaseManager().findPlayer(player.getUniqueId()).thenAccept(record -> {
                boolean hasDiscord = record.map(r -> r.hasDiscord()).orElse(false);
                if (hasDiscord && plugin.getDiscordAuthManager().isEnabled()) {
                    plugin.getDiscordAuthManager().requestAdminConfirmation(player, args)
                        .thenAccept(sent -> Bukkit.getScheduler().runTask(plugin, () -> {
                            if (sent) {
                                plugin.getLangManager().send(player, "gui.admin.discord-confirm-sent");
                            } else {
                                checkAdminPassword(player, args, opt.get());
                            }
                        }));
                } else {
                    checkAdminPassword(player, args, opt.get());
                }
            });
        }));
        return true;
    }

    private void checkAdminPassword(Player player, String[] args, String passwordHash) {
        plugin.getChatInputHandler().awaitInput(player, "gui.admin.admin-password-prompt", input -> {
            if (SecurityUtils.verifyPassword(input, passwordHash, plugin.getPepper())) {
                recordAdminSession(player.getUniqueId());
                handleCommand(player, args);
            } else {
                plugin.getLangManager().send(player, "gui.admin.admin-password-invalid");
                SoundUtils.error(player);
            }
        });
    }

    public void handleCommand(CommandSender sender, String[] args) {
        if (args.length == 0) {
            if (sender instanceof Player player) {
                plugin.getGuiManager().openAdmin(player);
            } else {
                sender.sendMessage("This command is for players. Use /ladmin help for console commands.");
            }
            return;
        }

        LangManager lang = plugin.getLangManager();
        String sub = args[0].toLowerCase();
        switch (sub) {
            case "help", "помощь" -> sendHelp(sender);
            case "reload", "перезагрузка" -> {
                plugin.getConfigManager().reload();
                plugin.getLangManager().load();
                plugin.getQueueManager().stop();
                plugin.getQueueManager().start();
                plugin.getLimboManager().initialize();
                if (plugin.getDiscordAuthManager() != null) {
                    plugin.getDiscordAuthManager().shutdown();
                    plugin.getDiscordAuthManager().initialize();
                }
                lang.send(sender, "general.reloaded");
            }
            case "unlock", "разблокировать" -> {
                if (args.length < 2) return;
                String target = args[1];
                plugin.getDatabaseManager().findPlayerByName(target).thenAccept(record -> {
                    if (record.isEmpty()) lang.send(sender, "general.player-not-found", Map.of("player", target));
                    else plugin.getBruteForceProtection().unlockAccount(record.get().uuid()).thenRun(() -> lang.send(sender, "commands.admin-unlock", Map.of("player", target)));
                });
            }
            case "unblockip", "разблокироватьайпи" -> {
                if (args.length < 2) return;
                String partialIp = args[1];
                plugin.getDatabaseManager().getRawIpFromBlocked(partialIp).thenAccept(optIp -> {
                    if (optIp.isEmpty()) lang.send(sender, "commands.ip-not-found");
                    else plugin.getDatabaseManager().clearIpBlock(optIp.get()).thenRun(() -> lang.send(sender, "commands.ip-unblocked", Map.of("ip", optIp.get())));
                });
            }
            case "session", "сессия" -> {
                if (args.length < 3) return;
                if (args[1].equalsIgnoreCase("reset") || args[1].equalsIgnoreCase("сброс")) {
                    String target = args[2];
                    plugin.getDatabaseManager().findPlayerByName(target).thenAccept(record -> {
                        if (record.isEmpty()) lang.send(sender, "general.player-not-found", Map.of("player", target));
                        else plugin.getSessionManager().invalidate(record.get().uuid())
                            .thenRun(() -> {
                                lang.send(sender, "commands.session-reset-for", Map.of("player", target));
                                Player p = Bukkit.getPlayer(record.get().uuid());
                                if (p != null) Bukkit.getScheduler().runTask(plugin, () -> p.kick(lang.component("commands.session-reset")));
                            });
                    });
                }
            }
            case "info", "инфо" -> {
                if (args.length < 2) return;
                String target = args[1];
                plugin.getDatabaseManager().findPlayerByName(target).thenAccept(record -> {
                    if (record.isEmpty()) lang.send(sender, "general.player-not-found", Map.of("player", target));
                    else {
                        DatabaseManager.PlayerRecord pr = record.get();
                        plugin.getDatabaseManager().getAlts(pr.lastIp()).thenAccept(alts -> {
                            lang.send(sender, "commands.admin-info-header", Map.of("player", pr.username()));
                            sender.sendMessage("§8» §7UUID: §f" + pr.uuid());
                            sender.sendMessage("§8» §7IP: §f" + (pr.lastIp() != null ? pr.lastIp() : "---"));
                            sender.sendMessage("§8» §7Discord: §f" + (pr.hasDiscord() ? "Linked" : "No"));
                            sender.sendMessage("§8» §7Alts: §f" + String.join(", ", alts));
                        });
                    }
                });
            }
            case "setadminpass", "установитьадминпароль" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Only players.");
                    return;
                }
                plugin.getChatInputHandler().awaitInput(player, "gui.admin.admin-password-set-prompt", pass -> {
                    plugin.getServer().getAsyncScheduler().runNow(plugin, t -> {
                        String hash = SecurityUtils.hashPassword(pass, plugin.getPepper());
                        plugin.getDatabaseManager().setAdminPassword(player.getUniqueId(), hash)
                            .thenRun(() -> lang.send(player, "gui.admin.admin-password-set"));
                    });
                });
            }
            case "delete", "удалить" -> {
                if (args.length < 2) return;
                doDeletePlayer(sender, args[1]);
            }
            case "amnesty", "амнистия" -> {
                plugin.getDatabaseManager().clearAllIpBlocks()
                    .thenCompose(unused -> plugin.getDatabaseManager().unlockAllAccounts())
                    .thenRun(() -> {
                        lang.send(sender, "commands.amnesty-done", Map.of("sender", sender.getName()));
                        plugin.getLogManager().database(
                            sender instanceof Player p ? p.getUniqueId() : null,
                            "AMNESTY",
                            "By " + sender.getName(),
                            null
                        );
                    });
            }
            default -> lang.send(sender, "general.unknown-command");
        }
    }

    private void doDeletePlayer(CommandSender sender, String target) {
        LangManager lang = plugin.getLangManager();
        plugin.getDatabaseManager().findPlayerByName(target).thenAccept(record -> {
            if (record.isEmpty()) { lang.send(sender, "general.player-not-found", Map.of("player", target)); return; }
            UUID uuid = record.get().uuid();
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player online = Bukkit.getPlayer(uuid);
                if (online != null) online.kick(lang.component("commands.admin-delete-kick"));
            });
            plugin.getDatabaseManager().deletePlayer(uuid).thenRun(() -> {
                plugin.getAuthManager().cleanup(uuid);
                plugin.getSessionManager().invalidate(uuid);
                lang.send(sender, "commands.admin-delete-success", Map.of("player", target));
                plugin.getLogManager().database(uuid, "PLAYER_DELETED", "By " + sender.getName(), null);
            });
        });
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage("§d§lLoveAuth Admin Help:");
        sender.sendMessage("§f/ladmin §7- Open Admin GUI");
        sender.sendMessage("§f/ladmin reload §7- Reload configuration");
        sender.sendMessage("§f/ladmin unlock <player> §7- Unlock account");
        sender.sendMessage("§f/ladmin unblockip <ip> §7- Unblock IP");
        sender.sendMessage("§f/ladmin info <player> §7- Detailed info & alts");
        sender.sendMessage("§f/ladmin session reset <player> §7- Reset session");
        sender.sendMessage("§f/ladmin setadminpass §7- Set admin password");
        sender.sendMessage("§f/ladmin delete <player> §7- Delete player data");
        sender.sendMessage("§f/ladmin amnesty §7- Clear all blocks");
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("loveauth.admin")) return List.of();
        if (args.length == 1) return List.of("help", "reload", "unlock", "unblockip", "session", "info", "setadminpass", "delete", "amnesty", "помощь", "перезагрузка", "разблокировать", "разблокироватьайпи", "сессия", "инфо", "установитьадминпароль", "удалить", "амнистия");
        if (args.length == 2) {
            String sub = args[0].toLowerCase();
            if (sub.equals("unlock") || sub.equals("info") || sub.equals("delete") || sub.equals("разблокировать") || sub.equals("инфо") || sub.equals("удалить")) return null;
            if (sub.equals("session") || sub.equals("сессия")) return List.of("reset", "сброс");
        }
        if (args.length == 3) {
            String sub = args[0].toLowerCase();
            if ((sub.equals("session") || sub.equals("сессия")) && (args[1].equalsIgnoreCase("reset") || args[1].equalsIgnoreCase("сброс"))) return null;
        }
        return List.of();
    }
}
