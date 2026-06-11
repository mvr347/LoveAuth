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

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public final class LAdminCommand implements CommandExecutor, TabCompleter {
    private final LoveAuth plugin;
    private static final DateTimeFormatter DF = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault());

    public LAdminCommand(LoveAuth plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        LangManager lang = plugin.getLangManager();
        if (!sender.hasPermission("loveauth.admin")) {
            lang.send(sender, "general.no-permission");
            return true;
        }

        if (args.length == 0) {
            if (sender instanceof Player player) plugin.getGuiManager().openAdmin(player);
            else sender.sendMessage("Use /ladmin panel|unlock|kick|info");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "panel" -> {
                if (sender instanceof Player player) plugin.getGuiManager().openAdmin(player);
                else sender.sendMessage("Only players can open the panel.");
            }
            case "unlock" -> {
                if (args.length < 2) return false;
                String target = args[1];
                plugin.getDatabaseManager().findPlayerByName(target).thenAccept(record -> {
                    if (record.isEmpty()) lang.send(sender, "general.player-not-found", Map.of("player", target));
                    else {
                        plugin.getBruteForceProtection().unlockAccount(record.get().uuid()).thenRun(() -> lang.send(sender, "commands.admin-unlock", Map.of("player", target)));
                    }
                });
            }
            case "kick" -> {
                if (args.length < 2) return false;
                Player target = Bukkit.getPlayer(args[1]);
                if (target == null) lang.send(sender, "general.player-not-found", Map.of("player", args[1]));
                else {
                    target.kick(lang.component("commands.admin-kick-reason"));
                    lang.send(sender, "general.player-kicked", Map.of("player", target.getName()));
                }
            }
            case "info" -> {
                if (args.length < 2) return false;
                String target = args[1];
                plugin.getDatabaseManager().findPlayerByName(target).thenAccept(record -> {
                    if (record.isEmpty()) lang.send(sender, "general.player-not-found", Map.of("player", target));
                    else {
                        DatabaseManager.PlayerRecord pr = record.get();
                        lang.send(sender, "commands.admin-info-header", Map.of("player", pr.username()));
                        lang.send(sender, "commands.admin-info-uuid", Map.of("uuid", pr.uuid().toString()));
                        lang.send(sender, "commands.admin-info-registered", Map.of("date", DF.format(Instant.ofEpochSecond(pr.registeredAt()))));
                        lang.send(sender, "commands.admin-info-locked", Map.of("status", String.valueOf(pr.locked())));
                        lang.send(sender, "commands.admin-info-password", Map.of("status", String.valueOf(pr.hasPassword())));
                    }
                });
            }
            default -> lang.send(sender, "general.unknown-command");
        }
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            return List.of("panel", "unlock", "kick", "info").stream().filter(s -> s.startsWith(args[0].toLowerCase())).toList();
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("unlock") || args[0].equalsIgnoreCase("kick") || args[0].equalsIgnoreCase("info"))) {
            return null; // suggest players
        }
        return Collections.emptyList();
    }
}
