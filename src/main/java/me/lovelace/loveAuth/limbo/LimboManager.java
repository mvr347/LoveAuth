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
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class LimboManager {
    /** Margin added on top of the configured auth timeout so the cache entry outlives the kick that's supposed to clear it. */
    private static final long EXPIRY_MARGIN_SECONDS = 300;
    private final LoveAuth plugin;
    private final ConfigManager config;
    private final LangManager lang;
    private final LogManager log;
    private final Map<UUID, Location> originalLocations = new ConcurrentHashMap<>();
    private final Cache<UUID, PlayerState> frozenPlayers;
    // Plain key set mirrored alongside frozenPlayers, read-only by restoreAllFrozenSync() at
    // shutdown. That was the ONLY call site in the whole class that ever touched
    // frozenPlayers.asMap().keySet() - and calling it for the first time ever, on the main
    // thread, inside onDisable() during a full server stop, crashed with NoClassDefFoundError
    // on the shaded/relocated Caffeine's BoundedLocalCache$KeySetView (2026-09-25 production
    // log): its class had never been loaded before and by then the plugin's classloader can no
    // longer resolve it. A plain JDK Set needs no lazy classloading of Caffeine's internal view
    // classes, so restoreAllFrozenSync() now reads this instead of ever calling keySet().
    private final java.util.Set<UUID> frozenUuids = ConcurrentHashMap.newKeySet();
    private World limboWorld;
    // Players currently being moved by this manager itself. PlayerProtectionListener.onTeleport
    // cancels every teleport of an unauthenticated player out of limbo - including cleanup()'s
    // teleport back on quit, which silently lost the return point and stranded players in limbo.
    private final java.util.Set<UUID> teleportBypass = ConcurrentHashMap.newKeySet();
    // Players whose location will be set by someone else right after restore() (register-spawn).
    private final java.util.Set<UUID> skipLocationRestore = ConcurrentHashMap.newKeySet();
    private final NamespacedKey returnKey;

    public LimboManager(LoveAuth plugin, ConfigManager config, LangManager lang, LogManager log) {
        this.plugin = plugin;
        this.config = config;
        this.lang = lang;
        this.log = log;
        this.returnKey = new NamespacedKey("loveauth", "return_location");
        this.frozenPlayers = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(config.getAuthTimeoutSeconds() + EXPIRY_MARGIN_SECONDS))
                .build();
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

    /** @return true if this was a cross-world teleport (client shows a loading screen). */
    public boolean sendToLimbo(Player player) {
        if (!config.isLimboEnabled() || limboWorld == null) return false;

        Location loc = player.getLocation();
        boolean crossWorld = !isLimboWorld(loc.getWorld());
        if (crossWorld) {
            originalLocations.put(player.getUniqueId(), loc);
            saveReturnLocation(player, loc);
        }

        freeze(player);
        teleport(player, new Location(limboWorld, 0.5, 100, 0.5));
        return crossWorld;
    }

    public boolean isTeleportBypassed(UUID uuid) {
        return teleportBypass.contains(uuid);
    }

    private boolean isLimboWorld(World world) {
        return world != null && world.getName().equals(config.getLimboWorldName());
    }

    /** Teleport issued by this manager: exempt from PlayerProtectionListener's cancel. */
    private boolean teleport(Player player, Location target) {
        UUID uuid = player.getUniqueId();
        teleportBypass.add(uuid);
        try {
            return player.teleport(target);
        } finally {
            teleportBypass.remove(uuid);
        }
    }

    private void saveReturnLocation(Player player, Location loc) {
        String encoded = ReturnLocationCodec.encode(new ReturnLocationCodec.Point(
                loc.getWorld().getName(), loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch()));
        if (encoded != null) {
            player.getPersistentDataContainer().set(returnKey, PersistentDataType.STRING, encoded);
        }
    }

    private Location readReturnLocation(Player player) {
        String raw = player.getPersistentDataContainer().get(returnKey, PersistentDataType.STRING);
        ReturnLocationCodec.Point p = ReturnLocationCodec.decode(raw);
        if (p == null) return null;
        World world = Bukkit.getWorld(p.world());
        if (world == null || isLimboWorld(world)) return null;
        return new Location(world, p.x(), p.y(), p.z(), p.yaw(), p.pitch());
    }

    private void clearReturnLocation(Player player) {
        player.getPersistentDataContainer().remove(returnKey);
    }

    /** Memory first, then the PDC copy that survives quits and restarts. */
    private Location resolveReturnLocation(Player player, Location fromMemory) {
        if (fromMemory != null && fromMemory.getWorld() != null && !isLimboWorld(fromMemory.getWorld())) return fromMemory;
        return readReturnLocation(player);
    }

    /** limbo.fallback-world, else register-spawn.world, else the first non-limbo world. */
    private Location fallbackSpawn() {
        for (String name : new String[]{config.getLimboFallbackWorld(), config.getRegisterSpawnWorld()}) {
            if (name == null || name.isBlank()) continue;
            World w = Bukkit.getWorld(name);
            if (w != null && !isLimboWorld(w)) return w.getSpawnLocation();
        }
        for (World w : Bukkit.getWorlds()) {
            if (!isLimboWorld(w)) return w.getSpawnLocation();
        }
        return null;
    }

    /**
     * Moves an authenticated player out of limbo: saved return point, else fallback spawn.
     * Does nothing for a player not standing in the limbo world (beyond dropping a stale PDC entry).
     */
    private void leaveLimbo(Player player, Location fromMemory) {
        if (!isLimboWorld(player.getWorld())) {
            clearReturnLocation(player);
            return;
        }
        Location target = resolveReturnLocation(player, fromMemory);
        if (target == null) target = fallbackSpawn();
        if (target == null) return;
        if (teleport(player, target)) clearReturnLocation(player);
    }

    public void freeze(Player player) {
        if (frozenPlayers.asMap().containsKey(player.getUniqueId())) return;

        PlayerState state = new PlayerState(
                player.getGameMode(), player.getWalkSpeed(), player.getFlySpeed(),
                player.getAllowFlight(), player.isFlying(), player.isInvulnerable(), player.isInvisible()
        );
        frozenPlayers.put(player.getUniqueId(), state);
        frozenUuids.add(player.getUniqueId());

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

        boolean skipLocation = skipLocationRestore.remove(uuid);

        Bukkit.getScheduler().runTask(plugin, () -> {
            // Mirrors restoreAllFrozenSync()'s guard: if the player disconnected during this
            // deferred tick, PlayerQuitListener's cleanup() already restored and persisted their
            // real state - don't touch a stale Player reference on top of that.
            if (!player.isOnline()) return;
            // Runs for every auth path, frozen or not: a player who quit while in limbo (or whose
            // return teleport was once cancelled) can rejoin standing in limbo with a valid
            // session, and nothing else would ever move them out.
            if (skipLocation) {
                clearReturnLocation(player);
            } else {
                leaveLimbo(player, original);
            }
            if (wasFrozen) unfreeze(player);
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
        skipLocationRestore.add(player.getUniqueId());
    }

    public void cleanup(Player player) {
        UUID uuid = player.getUniqueId();
        if (frozenPlayers.getIfPresent(uuid) != null) {
            // The player is disconnecting while still frozen in the limbo world (never
            // finished logging in). Bukkit persists wherever they physically stand at
            // disconnect time, so without this teleport their playerdata would save the
            // limbo coordinates - permanently losing their real location, since
            // originalLocations is in-memory only and gets cleared right below.
            // The PDC copy is kept on failure, so the next join's restore() still finds it.
            Location original = resolveReturnLocation(player, originalLocations.get(uuid));
            if (original != null && teleport(player, original)) {
                clearReturnLocation(player);
            }
            unfreeze(player);
        }
        originalLocations.remove(uuid);
        skipLocationRestore.remove(uuid);
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
        for (UUID uuid : new java.util.ArrayList<>(frozenUuids)) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) continue;
            Location original = resolveReturnLocation(player, originalLocations.remove(uuid));
            if (original != null && teleport(player, original)) {
                clearReturnLocation(player);
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
        frozenUuids.remove(player.getUniqueId());
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
