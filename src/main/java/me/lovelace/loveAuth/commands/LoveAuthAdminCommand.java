package me.lovelace.loveAuth.commands;

import me.lovelace.loveAuth.LoveAuth;
import me.lovelace.loveAuth.database.DatabaseManager;
import me.lovelace.loveAuth.lang.LangManager;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Единая административная команда плагина: {@code /loveauthadmin <subcommand>}.
 * <p>
 * Раньше эта функциональность жила под {@code /ladmin}. Та команда остаётся
 * зарегистрированной (см. {@link #onCommand}) только как редирект с коротким
 * сообщением на новое имя — так игрок/админ, набравший её по привычке, не
 * натыкается на молчание, а получает понятную подсказку.
 */
public final class LoveAuthAdminCommand implements CommandExecutor, TabCompleter {
    /** Legacy command name kept registered purely to redirect players to /loveauthadmin. */
    private static final String LEGACY_COMMAND_NAME = "ladmin";

    private final LoveAuth plugin;

    public LoveAuthAdminCommand(LoveAuth plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (command.getName().equalsIgnoreCase(LEGACY_COMMAND_NAME)) {
            if (!sender.hasPermission("loveauth.admin")) {
                plugin.getLangManager().send(sender, "general.no-permission");
            } else {
                plugin.getLangManager().send(sender, "commands.admin-command-moved");
            }
            return true;
        }

        if (!sender.hasPermission("loveauth.admin")) {
            plugin.getLangManager().send(sender, "general.no-permission");
            return true;
        }

        handleCommand(sender, args);
        return true;
    }

    public void handleCommand(CommandSender sender, String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return;
        }

        LangManager lang = plugin.getLangManager();
        String sub = args[0].toLowerCase();
        switch (sub) {
            case "help", "помощь" -> sendHelp(sender);
            case "reload", "перезагрузка" -> {
                // loveauth.reload объявлен в plugin.yml (и как child loveauth.admin), но нигде
                // не проверялся: перезагрузка гейтилась только общим loveauth.admin, из-за чего
                // выдать право исключительно на reload было невозможно.
                if (!sender.hasPermission("loveauth.reload")) {
                    lang.send(sender, "general.no-permission");
                    return;
                }
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
            case "delete", "удалить" -> {
                if (args.length < 2) return;
                doDeletePlayer(sender, args[1]);
            }
            case "setfirstspawn", "установитьпервыйспавн" -> {
                if (!(sender instanceof Player player)) {
                    lang.send(sender, "general.player-only");
                    return;
                }
                plugin.getConfigManager().setRegisterSpawnLocation(player.getLocation());
                lang.send(sender, "commands.admin-setfirstspawn-success");
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
        LangManager lang = plugin.getLangManager();
        sender.sendMessage(lang.component("commands.admin-help-header"));
        sendAdminEntry(sender, "reload", "commands.admin-help-reload");
        sendAdminEntry(sender, "unlock [игрок]", "commands.admin-help-unlock");
        sendAdminEntry(sender, "unblockip [ip]", "commands.admin-help-unblockip");
        sendAdminEntry(sender, "info [игрок]", "commands.admin-help-info");
        sendAdminEntry(sender, "session reset [игрок]", "commands.admin-help-session-reset");
        sendAdminEntry(sender, "delete [игрок]", "commands.admin-help-delete");
        sendAdminEntry(sender, "setfirstspawn", "commands.admin-help-setfirstspawn");
        sendAdminEntry(sender, "amnesty", "commands.admin-help-amnesty");
        sender.sendMessage(lang.component("commands.admin-help-footer"));
    }

    private void sendAdminEntry(CommandSender sender, String cmd, String key) {
        LangManager lang = plugin.getLangManager();
        sender.sendMessage(lang.component("commands.admin-help-entry", Map.of("command", cmd, "description", lang.plain(key))));
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (command.getName().equalsIgnoreCase(LEGACY_COMMAND_NAME)) return List.of();
        if (!sender.hasPermission("loveauth.admin")) return List.of();
        if (args.length == 1) return List.of("help", "reload", "unlock", "unblockip", "session", "info", "delete", "setfirstspawn", "amnesty", "помощь", "перезагрузка", "разблокировать", "разблокироватьайпи", "сессия", "инфо", "удалить", "установитьпервыйспавн", "амнистия");
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
