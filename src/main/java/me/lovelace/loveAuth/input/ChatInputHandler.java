package me.lovelace.loveAuth.input;

import io.papermc.paper.event.player.AsyncChatEvent;
import me.lovelace.loveAuth.LoveAuth;
import me.lovelace.loveAuth.lang.LangManager;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.scheduler.BukkitTask;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public final class ChatInputHandler implements Listener {
    private final LoveAuth plugin;
    private final LangManager lang;
    private final Map<UUID, PendingInput> awaitingInput = new ConcurrentHashMap<>();

    public ChatInputHandler(LoveAuth plugin, LangManager lang) {
        this.plugin = plugin;
        this.lang = lang;
    }

    public void awaitInput(Player player, String promptKey, Consumer<String> callback) {
        cleanup(player.getUniqueId());
        lang.sendActionBar(player, promptKey, Collections.emptyMap());
        // Action bar text fades after a few seconds in vanilla - keep re-sending it
        // periodically so the prompt stays visible for as long as the player is typing.
        BukkitTask refreshTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (player.isOnline()) {
                lang.sendActionBar(player, promptKey, Collections.emptyMap());
            }
        }, 50L, 50L);
        awaitingInput.put(player.getUniqueId(), new PendingInput(callback, refreshTask));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        PendingInput pending = awaitingInput.remove(player.getUniqueId());
        if (pending == null) return;

        pending.refreshTask().cancel();
        event.setCancelled(true);
        String message = PlainTextComponentSerializer.plainText().serialize(event.originalMessage());

        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) pending.callback().accept(message);
        });
    }

    public void cleanup(UUID uuid) {
        PendingInput pending = awaitingInput.remove(uuid);
        if (pending != null) pending.refreshTask().cancel();
    }

    private record PendingInput(Consumer<String> callback, BukkitTask refreshTask) {}
}
