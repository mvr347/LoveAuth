package me.lovelace.loveAuth.commands;

import me.lovelace.loveAuth.LoveAuth;
import me.lovelace.loveAuth.database.DatabaseManager;
import me.lovelace.loveAuth.lang.LangManager;
import me.lovelace.loveAuth.security.SecurityUtils;
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

public final class LAdminCommand implements CommandExecutor, TabCompleter {
    private final LoveAuth plugin;

    public LAdminCommand(LoveAuth plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("loveauth.admin")) {
            plugin.getLangManager().send(sender, "general.no-permission");
            return true;
        }

        if (sender instanceof Player player && !player.isOp()) {
            plugin.getDatabaseManager().getAdminPassword(player.getUniqueId()).thenAccept(opt -> {
                if (opt.isEmpty()) {
                    plugin.getLangManager().send(player, "gui.admin.admin-password-not-set");
                } else {
                    handleCommand(sender, args);
                }
            });
            return true;
        }

        handleCommand(sender, args);
        return true;
    }

    private void handleCommand(CommandSender sender, String[] args) {
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
                lang.send(player, "gui.admin.admin-password-set-prompt");
                plugin.getChatInputHandler().awaitInput(player, "gui.admin.admin-password-set-prompt", pass -> {
                    plugin.getServer().getAsyncScheduler().runNow(plugin, t -> {
                        String hash = SecurityUtils.hashPassword(pass, plugin.getPepper());
                        plugin.getDatabaseManager().setAdminPassword(player.getUniqueId(), hash)
                            .thenRun(() -> lang.send(player, "gui.admin.admin-password-set"));
                    });
                });
            }
            default -> lang.send(sender, "general.unknown-command");
        }
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
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("loveauth.admin")) return List.of();
        if (args.length == 1) return List.of("help", "reload", "unlock", "unblockip", "session", "info", "setadminpass", "помощь", "перезагрузка", "разблокировать", "разблокироватьайпи", "сессия", "инфо", "установитьадминпароль");
        if (args.length == 2) {
            String sub = args[0].toLowerCase();
            if (sub.equals("unlock") || sub.equals("info") || sub.equals("разблокировать") || sub.equals("инфо")) return null;
            if (sub.equals("session") || sub.equals("сессия")) return List.of("reset", "сброс");
        }
        if (args.length == 3) {
            String sub = args[0].toLowerCase();
            if ((sub.equals("session") || sub.equals("сессия")) && (args[1].equalsIgnoreCase("reset") || args[1].equalsIgnoreCase("сброс"))) return null;
        }
        return List.of();
    }
}
