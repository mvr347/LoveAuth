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
    // Sentinel callback used to mark an entry as "already consumed, still draining" - see onChat().
    private static final Consumer<String> SWALLOWED = message -> {};

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
        UUID uuid = player.getUniqueId();

        // A plain remove() here left a window open: a second chat message sent by the same
        // player (double Enter, a pasted second line, a macro) before this callback's runTask()
        // actually ran on the next tick would find no pending entry left, fall through
        // uncancelled, and get broadcast to public chat verbatim - including a raw password
        // typed during login/register. Swap the entry for a "swallow" placeholder instead, so
        // every message that arrives while a response is still in flight gets cancelled too,
        // without invoking the real callback a second time.
        PendingInput[] originalHolder = new PendingInput[1];
        PendingInput[] markerHolder = new PendingInput[1];
        awaitingInput.computeIfPresent(uuid, (key, current) -> {
            originalHolder[0] = current;
            PendingInput marker = new PendingInput(SWALLOWED, current.refreshTask());
            markerHolder[0] = marker;
            return marker;
        });
        PendingInput pending = originalHolder[0];
        if (pending == null) return;

        event.setCancelled(true);
        if (pending.callback() == SWALLOWED) return; // an earlier message this tick already consumed the real input

        pending.refreshTask().cancel();
        PendingInput marker = markerHolder[0];
        String message = PlainTextComponentSerializer.plainText().serialize(event.originalMessage());

        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) pending.callback().accept(message);
            // Only clears the placeholder if nothing registered a fresh prompt in the meantime
            // (e.g. a re-prompt after invalid input) - otherwise this would wipe out that new
            // state and the player's next, unrelated chat messages would be silently swallowed.
            awaitingInput.remove(uuid, marker);
        });
    }

    public void cleanup(UUID uuid) {
        PendingInput pending = awaitingInput.remove(uuid);
        if (pending != null) pending.refreshTask().cancel();
    }

    private record PendingInput(Consumer<String> callback, BukkitTask refreshTask) {}
}
