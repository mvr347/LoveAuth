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

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public final class ChatInputHandler implements Listener {
    private final LoveAuth plugin;
    private final LangManager lang;
    private final Map<UUID, Consumer<String>> awaitingInput = new ConcurrentHashMap<>();

    public ChatInputHandler(LoveAuth plugin, LangManager lang) {
        this.plugin = plugin;
        this.lang = lang;
    }

    public void awaitInput(Player player, String promptKey, Consumer<String> callback) {
        lang.send(player, promptKey);
        awaitingInput.put(player.getUniqueId(), callback);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        Consumer<String> callback = awaitingInput.remove(player.getUniqueId());
        if (callback == null) return;

        event.setCancelled(true);
        String message = PlainTextComponentSerializer.plainText().serialize(event.originalMessage());
        
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) callback.accept(message);
        });
    }

    public void cleanup(UUID uuid) {
        awaitingInput.remove(uuid);
    }
}
