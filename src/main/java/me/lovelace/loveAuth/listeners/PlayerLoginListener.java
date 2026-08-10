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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class PlayerLoginListener implements Listener {
    // AsyncPlayerPreLoginEvent runs off the main thread specifically so plugins can block on
    // things like this DB lookup - but an unbounded .join() would still hang this connection's
    // thread forever if the database stalls. Bound it instead of blocking indefinitely.
    private static final long DB_TIMEOUT_SECONDS = 15L;

    private final LoveAuth plugin;

    public PlayerLoginListener(LoveAuth plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        InetAddress address = event.getAddress();
        String ip = address.getHostAddress();
        LangManager lang = plugin.getLangManager();

        try {
            BruteForceProtection.IpBlockStatus status = plugin.getBruteForceProtection()
                    .getIpBlockStatus(ip)
                    .get(DB_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (status.blocked()) {
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED,
                        lang.component("block.ip-locked", Map.of("minutes", Long.toString(status.minutes()))));
                return;
            }

            plugin.getDatabaseManager().findPlayer(event.getUniqueId())
                    .get(DB_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .filter(record -> record.locked())
                    .ifPresent(record -> event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED,
                            lang.component("block.account-locked")));
        } catch (TimeoutException e) {
            plugin.getLogManager().errorKey("log.database-error",
                    Map.of("message", "Pre-login check timed out for " + ip), e);
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, lang.component("kick.auth-error"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, lang.component("kick.auth-error"));
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            plugin.getLogManager().errorKey("log.database-error",
                    Map.of("message", cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName()), cause);
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, lang.component("kick.auth-error"));
        }
    }
}
