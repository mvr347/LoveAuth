package me.lovelace.loveAuth.listeners;

import me.lovelace.loveAuth.LoveAuth;
import me.lovelace.loveAuth.auth.BruteForceProtection;
import me.lovelace.loveAuth.lang.LangManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;

import java.net.InetAddress;
import java.util.Map;

public final class PlayerLoginListener implements Listener {
    private final LoveAuth plugin;

    public PlayerLoginListener(LoveAuth plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        InetAddress address = event.getAddress();
        String ip = address.getHostAddress();
        LangManager lang = plugin.getLangManager();

        BruteForceProtection.IpBlockStatus status = plugin.getBruteForceProtection().getIpBlockStatus(ip).join();
        if (status.blocked()) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, lang.component("block.ip-locked", Map.of("minutes", Long.toString(status.minutes()))));
            return;
        }

        plugin.getDatabaseManager().findPlayer(event.getUniqueId()).thenAccept(record -> {
            if (record.isPresent() && record.get().locked()) {
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, lang.component("block.account-locked"));
            }
        }).join();
    }
}
