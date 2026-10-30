package me.lovelace.loveAuth.listeners;

import dev.lovelace.lovecore.api.auth.PlayerAuthenticatedEvent;
import me.lovelace.loveAuth.LoveAuth;
import me.lovelace.loveAuth.util.Anniversary;
import me.lovelace.loveAuth.util.Notify;
import org.bukkit.Bukkit;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

/**
 * Easter egg: logging in on the anniversary of the registration gets particles, a sound and a title. Each year is
 * celebrated once, even if the player logs in several times that day.
 */
public final class AnniversaryListener implements Listener {
    private final LoveAuth plugin;
    /** Players whose celebration is scheduled but has not run yet. */
    private final java.util.Set<UUID> pending = new java.util.HashSet<>();
    /** The last year celebrated in this run, so a relog before the database write lands cannot repeat it. */
    private final java.util.Map<UUID, Integer> celebrated = new java.util.HashMap<>();

    public AnniversaryListener(LoveAuth plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onAuthenticated(PlayerAuthenticatedEvent event) {
        if (!plugin.getConfig().getBoolean("easter-eggs.anniversary.enabled", true)) return;
        Player player = event.player();
        UUID uuid = player.getUniqueId();
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        if (celebrated.getOrDefault(uuid, 0) >= today.getYear() || pending.contains(uuid)) return;
        plugin.getDatabaseManager().findAnniversary(uuid).thenAccept(info -> {
            if (info.isEmpty()) return;
            int years = Anniversary.years(info.get().registeredAt(), today, zone);
            if (years < 1 || info.get().lastYear() >= today.getYear()) return;
            Bukkit.getScheduler().runTask(plugin, () -> celebrate(player, years, today.getYear()));
        });
    }

    private void celebrate(Player player, int years, int year) {
        UUID uuid = player.getUniqueId();
        if (!player.isOnline() || celebrated.getOrDefault(uuid, 0) >= year || !pending.add(uuid)) return;
        // The login restores the player's place and mode a tick or two later; effects wait for that.
        long delay = Math.max(5L, plugin.getConfig().getLong("easter-eggs.anniversary.delay-ticks", 20L));
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            pending.remove(uuid);
            // A player who left during the delay saw nothing, so the year is not used up: the next login celebrates.
            if (!player.isOnline()) return;
            celebrated.put(uuid, year);
            plugin.getDatabaseManager().markAnniversary(uuid, year);
            var at = player.getLocation().add(0, 1.2, 0);
            player.getWorld().spawnParticle(Particle.FIREWORK, at, 60, 0.6, 0.8, 0.6, 0.05);
            player.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, at, 20, 0.6, 0.8, 0.6, 0.0);
            player.playSound(player.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 1.0f, 1.0f);
            player.playSound(player.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 1.0f, 1.0f);
            Map<String, String> placeholders = Map.of("years", Integer.toString(years), "player", player.getName());
            Notify.title(player, plugin.getLangManager().component("anniversary.title", placeholders),
                    plugin.getLangManager().component("anniversary.subtitle", placeholders));
            me.lovelace.loveAuth.integration.SecretsHook.discover(uuid, "anniversary");
        }, delay);
    }
}
