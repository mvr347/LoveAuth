package me.lovelace.loveAuth.commands;

import me.lovelace.loveAuth.LoveAuth;
import me.lovelace.loveAuth.lang.LangManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class LoveAuthCommand implements CommandExecutor, TabCompleter {
    private final LoveAuth plugin;

    public LoveAuthCommand(LoveAuth plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) return true;
        
        if (args.length == 0 || args[0].equalsIgnoreCase("account")) {
            if (plugin.getAuthManager().isAuthenticated(player.getUniqueId())) {
                plugin.getGuiManager().openAccount(player);
            } else {
                plugin.getGuiManager().openAuthMethod(player);
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("reload") && player.hasPermission("loveauth.reload")) {
            plugin.reloadConfig();
            plugin.getConfigManager().reload();
            plugin.getLangManager().reload();
            plugin.getLangManager().send(player, "general.reloaded");
            return true;
        }

        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            List<String> list = new ArrayList<>();
            list.add("account");
            if (sender.hasPermission("loveauth.reload")) list.add("reload");
            return list;
        }
        return Collections.emptyList();
    }
}
