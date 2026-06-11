package me.lovelace.loveAuth.input;

import me.lovelace.loveAuth.LoveAuth;
import me.lovelace.loveAuth.lang.LangManager;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public final class AnvilInputHandler implements Listener {
    private final LoveAuth plugin;
    private final LangManager lang;
    private final Map<UUID, Consumer<String>> awaitingInput = new ConcurrentHashMap<>();

    public AnvilInputHandler(LoveAuth plugin, LangManager lang) {
        this.plugin = plugin;
        this.lang = lang;
    }

    public void awaitInput(Player player, String promptKey, Consumer<String> callback) {
        Inventory anvil = Bukkit.createInventory(player, InventoryType.ANVIL, lang.component(promptKey));
        
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(lang.component("gui.filler-name"));
            item.setItemMeta(meta);
        }
        anvil.setItem(0, item);

        player.openInventory(anvil);
        awaitingInput.put(player.getUniqueId(), callback);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        Consumer<String> callback = awaitingInput.get(player.getUniqueId());
        if (callback == null) {
            return;
        }

        if (event.getInventory().getType() != InventoryType.ANVIL) {
            return;
        }

        event.setCancelled(true);

        if (event.getRawSlot() == 2) {
            AnvilInventory anvil = (AnvilInventory) event.getInventory();
            String result = anvil.getRenameText(); // Using deprecated but functional for now to avoid complexity
            if (result != null && !result.isBlank()) {
                awaitingInput.remove(player.getUniqueId());
                player.closeInventory();
                callback.accept(result);
            }
        }
    }

    public void cleanup(UUID uuid) {
        awaitingInput.remove(uuid);
    }
}
