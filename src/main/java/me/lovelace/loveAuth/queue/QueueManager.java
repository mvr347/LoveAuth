package me.lovelace.loveAuth.queue;

import me.lovelace.loveAuth.LoveAuth;
import me.lovelace.loveAuth.config.ConfigManager;
import me.lovelace.loveAuth.gui.QueueGui;
import me.lovelace.loveAuth.lang.LangManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.scheduler.BukkitTask;

import java.util.LinkedList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class QueueManager {
    private final LoveAuth plugin;
    private final ConfigManager config;
    private final LangManager lang;
    private final LinkedList<UUID> queue = new LinkedList<>();
    private final Map<UUID, Integer> priorities = new ConcurrentHashMap<>();
    private BukkitTask ticker;

    public QueueManager(LoveAuth plugin, ConfigManager config, LangManager lang) {
        this.plugin = plugin;
        this.config = config;
        this.lang = lang;
    }

    public void start() {
        if (!config.isQueueEnabled()) {
            return;
        }
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 40L, 40L);
    }

    public void stop() {
        if (ticker != null) {
            ticker.cancel();
        }
        queue.clear();
        priorities.clear();
    }

    public void addToQueue(Player player) {
        if (!config.isQueueEnabled() || player.hasPermission("loveauth.queue.bypass")) {
            plugin.getAuthManager().handleJoin(player);
            return;
        }

        UUID uuid = player.getUniqueId();
        if (queue.contains(uuid)) {
            return;
        }

        int priority = getPriority(player);
        priorities.put(uuid, priority);

        int index = 0;
        for (UUID queuedUuid : queue) {
            if (priority < priorities.getOrDefault(queuedUuid, 999)) {
                break;
            }
            index++;
        }
        queue.add(index, uuid);
        
        plugin.getGuiManager().openQueue(player);
    }

    public void removeFromQueue(UUID uuid) {
        queue.remove(uuid);
        priorities.remove(uuid);
    }

    public int getPosition(UUID uuid) {
        int pos = queue.indexOf(uuid);
        return pos == -1 ? -1 : pos + 1;
    }

    public int getTotal() {
        return queue.size();
    }

    private void tick() {
        if (queue.isEmpty()) {
            return;
        }

        int maxPlayers = config.getQueueMaxPlayers();
        int onlinePlayers = Bukkit.getOnlinePlayers().size();

        if (onlinePlayers < maxPlayers) {
            UUID next = queue.poll();
            if (next != null) {
                priorities.remove(next);
                Player player = Bukkit.getPlayer(next);
                if (player != null && player.isOnline()) {
                    lang.send(player, "queue.connecting");
                    plugin.getAuthManager().handleJoin(player);
                }
            }
        }

        for (UUID uuid : queue) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                int pos = getPosition(uuid);
                lang.sendActionBar(player, "actionbar.queue-position", Map.of("position", String.valueOf(pos), "total", String.valueOf(getTotal())));
                
                InventoryHolder holder = player.getOpenInventory().getTopInventory().getHolder();
                if (holder instanceof QueueGui queueGui) {
                    queueGui.refresh();
                } else if (!plugin.getAuthManager().isAuthenticated(uuid)) {
                    plugin.getGuiManager().openQueue(player);
                }
            }
        }
    }

    private int getPriority(Player player) {
        return player.getEffectivePermissions().stream()
                .filter(perm -> perm.getPermission().startsWith("loveauth.queue.priority."))
                .map(perm -> {
                    try {
                        return Integer.parseInt(perm.getPermission().substring("loveauth.queue.priority.".length()));
                    } catch (NumberFormatException e) {
                        return 999;
                    }
                })
                .min(Integer::compare)
                .orElse(999);
    }
}
