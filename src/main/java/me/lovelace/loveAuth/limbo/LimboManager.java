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
        if (limboWorld != null) {
            limboWorld.setGameRule(org.bukkit.GameRule.DO_DAYLIGHT_CYCLE, false);
            limboWorld.setGameRule(org.bukkit.GameRule.DO_MOB_SPAWNING, false);
            limboWorld.setGameRule(org.bukkit.GameRule.DO_WEATHER_CYCLE, false);
            limboWorld.setTime(6000L);
            
            // Physical platform
            Block block = limboWorld.getBlockAt(0, 99, 0);
            block.setType(Material.BARRIER);
            
            log.infoKey("log.limbo-created", Map.of("world", worldName));
        }
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

    public void restore(Player player) {
        Location original = originalLocations.remove(player.getUniqueId());
        
        // Redundancy: if no location saved or still in limbo, use default spawn
        if (original == null || original.getWorld().getName().equals(config.getLimboWorldName())) {
            original = Bukkit.getWorlds().get(0).getSpawnLocation();
        }
        
        Location target = original;
        Bukkit.getScheduler().runTask(plugin, () -> {
            player.teleport(target);
            unfreeze(player);
        });
    }

    public void unfreeze(Player player) {
        PlayerState state = frozenPlayers.getIfPresent(player.getUniqueId());
        if (state != null) {
            player.setGameMode(state.gameMode());
            player.setWalkSpeed(state.walkSpeed());
            player.setFlySpeed(state.flySpeed());
            player.setAllowFlight(state.allowFlight());
            player.setFlying(state.isFlying());
            player.setInvulnerable(state.isInvulnerable());
            player.setInvisible(state.isInvisible());
            frozenPlayers.invalidate(player.getUniqueId());
        } else {
            player.setGameMode(GameMode.SURVIVAL);
            player.setWalkSpeed(0.2f);
            player.setFlySpeed(0.1f);
            player.setAllowFlight(false);
            player.setFlying(false);
            player.setInvulnerable(false);
            player.setInvisible(false);
        }
    }

    private static class VoidGenerator extends ChunkGenerator {
        @Override
        public void generateNoise(@NotNull WorldInfo worldInfo, @NotNull Random random, int chunkX, int chunkZ, @NotNull ChunkData chunkData) {}
        @Override public boolean shouldGenerateCaves() { return false; }
        @Override public boolean shouldGenerateDecorations() { return false; }
        @Override public boolean shouldGenerateMobs() { return false; }
        @Override public boolean shouldGenerateStructures() { return false; }
    }

    private record PlayerState(GameMode gameMode, float walkSpeed, float flySpeed, boolean allowFlight, boolean isFlying, boolean isInvulnerable, boolean isInvisible) {}
}
