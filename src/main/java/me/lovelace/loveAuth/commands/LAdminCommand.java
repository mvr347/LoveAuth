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

        if (args.length == 0) {
            if (sender instanceof Player player) {
                plugin.getGuiManager().openAdmin(player);
            } else {
                sender.sendMessage("This command is for players. Use /ladmin help for console commands.");
            }
            return true;
        }

        LangManager lang = plugin.getLangManager();
        switch (args[0].toLowerCase()) {
            case "help" -> sendHelp(sender);
            case "reload" -> {
                plugin.getConfigManager().reload();
                plugin.getLangManager().load();
                lang.send(sender, "general.reloaded");
            }
            case "unlock" -> {
                if (args.length < 2) return false;
                String target = args[1];
                plugin.getDatabaseManager().findPlayerByName(target).thenAccept(record -> {
                    if (record.isEmpty()) lang.send(sender, "general.player-not-found", Map.of("player", target));
                    else plugin.getBruteForceProtection().unlockAccount(record.get().uuid()).thenRun(() -> lang.send(sender, "commands.admin-unlock", Map.of("player", target)));
                });
            }
            case "session" -> {
                if (args.length < 3 || !args[1].equalsIgnoreCase("reset")) return false;
                String target = args[2];
                plugin.getDatabaseManager().findPlayerByName(target).thenAccept(record -> {
                    if (record.isEmpty()) lang.send(sender, "general.player-not-found", Map.of("player", target));
                    else plugin.getSessionManager().invalidate(record.get().uuid())
                        .thenRun(() -> lang.send(sender, "commands.session-reset-for", Map.of("player", target)));
                });
            }
            case "setadminpass" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Only players can set admin password via chat input.");
                    return true;
                }
                lang.send(player, "gui.admin.admin-password-set-prompt");
                plugin.getChatInputHandler().awaitInput(player, "gui.admin.admin-password-set-prompt", pass -> {
                    plugin.getServer().getAsyncScheduler().runNow(plugin, t -> {
                        String hash = SecurityUtils.hashPassword(pass);
                        plugin.getDatabaseManager().setAdminPassword(player.getUniqueId(), hash)
                            .thenRun(() -> lang.send(player, "gui.admin.admin-password-set"));
                    });
                });
            }
            default -> lang.send(sender, "general.unknown-command");
        }

        return true;
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage("§d§lLoveAuth Admin Help:");
        sender.sendMessage("§f/ladmin §7- Open Admin GUI");
        sender.sendMessage("§f/ladmin reload §7- Reload configuration");
        sender.sendMessage("§f/ladmin unlock <player> §7- Unlock a player account");
        sender.sendMessage("§f/ladmin session reset <player> §7- Reset player session");
        sender.sendMessage("§f/ladmin setadminpass §7- Set your admin password");
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("loveauth.admin")) return List.of();
        if (args.length == 1) return List.of("help", "reload", "unlock", "session", "setadminpass");
        if (args.length == 2 && args[0].equalsIgnoreCase("unlock")) return null;
        if (args.length == 2 && args[0].equalsIgnoreCase("session")) return List.of("reset");
        if (args.length == 3 && args[0].equalsIgnoreCase("session") && args[1].equalsIgnoreCase("reset")) return null;
        return List.of();
    }
}
