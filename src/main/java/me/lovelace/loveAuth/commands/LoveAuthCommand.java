package me.lovelace.loveAuth.commands;

import me.lovelace.loveAuth.LoveAuth;
import me.lovelace.loveAuth.auth.AuthManager;
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

public final class LoveAuthCommand implements CommandExecutor, TabCompleter {
    private final LoveAuth plugin;

    public LoveAuthCommand(LoveAuth plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cOnly for players.");
            return true;
        }

        AuthManager auth = plugin.getAuthManager();
        LangManager lang = plugin.getLangManager();
        UUID uuid = player.getUniqueId();

        if (args.length == 0) {
            if (auth.isAuthenticated(uuid)) plugin.getGuiManager().openAccount(player);
            else if (auth.isRegisteredCached(uuid)) plugin.getGuiManager().openAuthMethod(player);
            else plugin.getGuiManager().openRegister(player);
            return true;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "help", "помощь" -> sendHelp(player);
            case "register", "регистрация" -> {
                if (auth.isRegisteredCached(uuid)) lang.send(player, "auth.already-registered");
                else auth.requestRegistration(player);
            }
            case "login", "вход" -> {
                if (auth.isAuthenticated(uuid)) lang.send(player, "auth.already-logged-in");
                else if (!auth.isRegisteredCached(uuid)) lang.send(player, "auth.not-registered");
                else auth.requestPasswordLogin(player);
            }
            case "account", "аккаунт" -> {
                if (!auth.isAuthenticated(uuid)) lang.send(player, "auth.not-logged-in");
                else if (args.length > 1) plugin.getGuiManager().openAccount(player, args[1]);
                else plugin.getGuiManager().openAccount(player);
            }
            case "password", "пароль" -> {
                if (!auth.isAuthenticated(uuid)) lang.send(player, "auth.not-logged-in");
                else auth.requestPasswordChange(player);
            }
            case "logout", "выход" -> {
                if (!auth.isAuthenticated(uuid)) lang.send(player, "auth.not-logged-in");
                else auth.forceLogout(uuid).thenRun(() -> Bukkit.getScheduler().runTask(plugin, () -> player.kick(lang.component("commands.session-reset"))));
            }
            default -> lang.send(player, "general.unknown-command");
        }

        return true;
    }

    private void sendHelp(Player player) {
        LangManager lang = plugin.getLangManager();
        player.sendMessage(lang.plain("commands.help-header"));
        sendEntry(player, "register", "commands.help-register");
        sendEntry(player, "login", "commands.help-login");
        sendEntry(player, "account", "commands.help-account");
        sendEntry(player, "password", "commands.help-password-change");
    }

    private void sendEntry(Player player, String cmd, String key) {
        player.sendMessage(plugin.getLangManager().component("commands.help-entry", Map.of("command", cmd, "description", plugin.getLangManager().plain(key))));
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 1) return List.of("help", "register", "login", "account", "password", "logout", "помощь", "регистрация", "вход", "аккаунт", "пароль", "выход");
        return List.of();
    }
}
