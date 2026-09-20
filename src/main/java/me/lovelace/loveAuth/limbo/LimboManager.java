package me.lovelace.loveAuth.limbo;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import me.lovelace.loveAuth.LoveAuth;
import me.lovelace.loveAuth.config.ConfigManager;
import me.lovelace.loveAuth.lang.LangManager;
import me.lovelace.loveAuth.util.LogManager;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public final class LimboManager {
    private final LoveAuth plugin;
    private final ConfigManager config;
    private final LangManager lang;
    private final LogManager log;
    private final Map<UUID, Location> originalLocations = new ConcurrentHashMap<>();
    private final Cache<UUID, PlayerState> frozenPlayers = Caffeine.newBuilder()
            .expireAfterWrite(1, TimeUnit.HOURS)
            .build();
    private World limboWorld;

    public LimboManager(LoveAuth plugin, ConfigManager config, LangManager lang, LogManager log) {
        this.plugin = plugin;
        this.config = config;
        this.lang = lang;
        this.log = log;
    }

    public void initialize() {
        if (!config.isLimboEnabled()) return;
        String worldName = config.getLimboWorldName();
        limboWorld = Bukkit.getWorld(worldName);
        if (limboWorld == null) {
            WorldCreator creator = new WorldCreator(worldName);
            creator.generator(new VoidGenerator());
            creator.generateStructures(false);
            limboWorld = creator.createWorld();
        }
        if (limboWorld == null) {
            log.warnKey("log.limbo-create-failed", Map.of("world", worldName));
            return;
        }
        limboWorld.setGameRule(org.bukkit.GameRule.DO_DAYLIGHT_CYCLE, false);
        limboWorld.setGameRule(org.bukkit.GameRule.DO_MOB_SPAWNING, false);
        limboWorld.setGameRule(org.bukkit.GameRule.DO_WEATHER_CYCLE, false);
        limboWorld.setTime(6000L);

        // Physical platform
        Block block = limboWorld.getBlockAt(0, 99, 0);
        block.setType(Material.BARRIER);

        log.infoKey("log.limbo-created", Map.of("world", worldName));
    }

    public void sendToLimbo(Player player) {
        if (!config.isLimboEnabled() || limboWorld == null) return;
        
        Location loc = player.getLocation();
        if (!loc.getWorld().getName().equals(config.getLimboWorldName())) {
            originalLocations.put(player.getUniqueId(), loc);
        }
        
        freeze(player);
        Location limboLocation = new Location(limboWorld, 0.5, 100, 0.5);
        player.teleport(limboLocation);
    }

    public void freeze(Player player) {
        if (frozenPlayers.asMap().containsKey(player.getUniqueId())) return;

        PlayerState state = new PlayerState(
                player.getGameMode(), player.getWalkSpeed(), player.getFlySpeed(),
                player.getAllowFlight(), player.isFlying(), player.isInvulnerable(), player.isInvisible()
        );
        frozenPlayers.put(player.getUniqueId(), state);

        player.setGameMode(GameMode.ADVENTURE);
        player.setAllowFlight(true);
        player.setFlying(true);
        player.setWalkSpeed(0f);
        player.setFlySpeed(0f);
        player.setInvulnerable(true);
        player.setInvisible(true);
    }

    /**
     * Called once a join finishes authenticating. If the player was never actually
     * frozen this session - a valid session skips limbo entirely and auto-logs in via
     * {@code AuthManager#markAuthenticated} - there is nothing to restore: they're
     * already sitting exactly where Bukkit put them from their persisted playerdata,
     * with their real gamemode intact. Teleporting to a fallback spawn or force-resetting
     * gamemode in that case is the bug this method used to have - every ordinary relogin
     * got silently teleported to world[0]'s spawn and dropped into survival.
     */
    public void restore(Player player) {
        UUID uuid = player.getUniqueId();
        boolean wasFrozen = frozenPlayers.getIfPresent(uuid) != null;
        Location original = originalLocations.remove(uuid);

        if (!wasFrozen) return;

        Bukkit.getScheduler().runTask(plugin, () -> {
            if (original != null && !original.getWorld().getName().equals(config.getLimboWorldName())) {
                player.teleport(original);
            }
            unfreeze(player);
        });
    }

    /**
     * Discards any pending "teleport back to original pre-limbo location" for this player
     * without unfreezing them yet - for a caller that's about to place the player somewhere
     * deliberately different right after {@link #restore} runs (e.g. register-spawn on first
     * registration), where "original" is meaningless anyway (just wherever Bukkit happened to
     * spawn a brand-new player) and restoring it first only means bouncing the player through
     * two rapid cross-world teleports a tick apart - which was observed landing them above the
     * ground at the final destination instead of on it, presumably a client-side dimension-
     * change/physics-settling race. {@link #restore}'s gamemode/flight unfreeze still runs as
     * normal; only the location-teleport half is skipped.
     */
    public void discardOriginalLocation(Player player) {
        originalLocations.remove(player.getUniqueId());
    }

    public void cleanup(Player player) {
        UUID uuid = player.getUniqueId();
        if (frozenPlayers.getIfPresent(uuid) != null) {
            // The player is disconnecting while still frozen in the limbo world (never
            // finished logging in). Bukkit persists wherever they physically stand at
            // disconnect time, so without this teleport their playerdata would save the
            // limbo coordinates - permanently losing their real location, since
            // originalLocations is in-memory only and gets cleared right below.
            Location original = originalLocations.get(uuid);
            if (original != null && !original.getWorld().getName().equals(config.getLimboWorldName())) {
                player.teleport(original);
            }
            unfreeze(player);
        }
        originalLocations.remove(uuid);
    }

    /**
     * Called from {@code onDisable()}. {@link #restore} normally defers the actual restore by
     * a tick via the scheduler, but Bukkit/Paper/Folia stop running a disabling plugin's
     * scheduled tasks, so that deferred restore would simply never happen. Any player still
     * frozen in limbo at shutdown would then have Bukkit persist whatever corrupted in-memory
     * state they're sitting in (ADVENTURE, flying, invisible, limbo coordinates) as their real
     * playerdata. Worse, the frozen-players cache is in-memory only and is empty again after a
     * restart, so {@link #restore} has no record telling it a restore is still owed - the next
     * join sees an "unfrozen" player already sitting in that broken state forever. Runs
     * synchronously, right now, instead of scheduling anything.
     */
    public void restoreAllFrozenSync() {
        for (UUID uuid : new java.util.ArrayList<>(frozenPlayers.asMap().keySet())) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) continue;
            Location original = originalLocations.remove(uuid);
            if (original != null && !original.getWorld().getName().equals(config.getLimboWorldName())) {
                player.teleport(original);
            }
            unfreeze(player);
        }
    }

    /** Only ever called while a frozen {@link PlayerState} exists - both call sites guard on it. */
    private void unfreeze(Player player) {
        PlayerState state = frozenPlayers.getIfPresent(player.getUniqueId());
        if (state == null) return;
        player.setGameMode(state.gameMode());
        player.setWalkSpeed(state.walkSpeed());
        player.setFlySpeed(state.flySpeed());
        player.setAllowFlight(state.allowFlight());
        player.setFlying(state.isFlying());
        player.setInvulnerable(state.isInvulnerable());
        player.setInvisible(state.isInvisible());
        frozenPlayers.invalidate(player.getUniqueId());
    }

    private static class VoidGenerator extends ChunkGenerator {
        @Override
        public void generateNoise(@NotNull WorldInfo worldInfo, @NotNull Random random, int chunkX, int chunkZ, @NotNull ChunkData chunkData) {}
        // shouldGenerateBedrock() defaults to true and was never overridden here, so every
        // chunk still got a real vanilla bedrock layer at the world floor despite generateNoise()
        // leaving everything else air - looked like a solid slab/box "carved out" of the void
        // instead of a fully empty world. shouldGenerateSurface()/shouldGenerateNoise() are
        // harmless no-ops on an all-air chunk, but disabled too so nothing here depends on that.
        @Override public boolean shouldGenerateNoise() { return false; }
        @Override public boolean shouldGenerateSurface() { return false; }
        @Override public boolean shouldGenerateBedrock() { return false; }
        @Override public boolean shouldGenerateCaves() { return false; }
        @Override public boolean shouldGenerateDecorations() { return false; }
        @Override public boolean shouldGenerateMobs() { return false; }
        @Override public boolean shouldGenerateStructures() { return false; }
    }

    private record PlayerState(GameMode gameMode, float walkSpeed, float flySpeed, boolean allowFlight, boolean isFlying, boolean isInvulnerable, boolean isInvisible) {}
}
