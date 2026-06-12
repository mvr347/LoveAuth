package me.lovelace.loveAuth.gui;

import me.lovelace.loveAuth.lang.LangManager;
import me.lovelace.loveAuth.queue.QueueManager;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

public final class QueueGui implements LoveAuthHolder {
    private final Player player;
    private final LangManager lang;
    private final QueueManager queue;
    private Inventory inventory;

    public QueueGui(Player player, LangManager lang, QueueManager queue) {
        this.player = player;
        this.lang = lang;
        this.queue = queue;
    }

    public void open() {
        this.inventory = Bukkit.createInventory(this, 27, lang.component("gui.queue.title"));
        refresh();
        player.openInventory(inventory);
    }

    public void refresh() {
        GuiManager.fillBackground(inventory, lang);

        int pos = queue.getPosition(player.getUniqueId());
        int total = queue.getTotal();
        String time = (pos * 5) + "s";

        ItemStack info = new ItemStack(Material.PAPER);
        ItemMeta infoMeta = info.getItemMeta();
        if (infoMeta != null) {
            infoMeta.displayName(lang.component("gui.queue.position-item"));
            infoMeta.lore(lang.lore("gui.queue.position-lore", Map.of(
                    "position", Integer.toString(pos),
                    "total", Integer.toString(total),
                    "time", time
            )));
            info.setItemMeta(infoMeta);
        }
        inventory.setItem(13, info);

        ItemStack refresh = new ItemStack(Material.SUNFLOWER);
        ItemMeta refreshMeta = refresh.getItemMeta();
        if (refreshMeta != null) {
            refreshMeta.displayName(lang.component("gui.queue.refresh-button"));
            refresh.setItemMeta(refreshMeta);
        }
        inventory.setItem(22, refresh);
    }

    @Override
    public @NotNull Inventory getInventory() { return inventory; }
}
