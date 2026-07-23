package me.lovelace.loveAuth.gui;

import me.lovelace.loveAuth.lang.LangManager;
import me.lovelace.loveAuth.queue.QueueManager;
import me.lovelace.loveAuth.util.HeadTextures;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
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
        GuiManager.standardFrame27(inventory, lang);
        inventory.setItem(0, GuiManager.playerHead(player, lang));

        int pos = queue.getPosition(player.getUniqueId());
        int total = queue.getTotal();
        String time = (pos * 5) + "s";

        ItemStack info = HeadTextures.createSkull(HeadTextures.HEAD_INFO,
                lang.component("gui.queue.position-item"),
                lang.lore("gui.queue.position-lore", Map.of(
                        "position", Integer.toString(pos),
                        "total", Integer.toString(total),
                        "time", time
                )));
        inventory.setItem(12, info);

        ItemStack refresh = HeadTextures.createSkull(HeadTextures.HEAD_BACK,
                lang.component("gui.queue.refresh-button"), Collections.emptyList());
        inventory.setItem(14, refresh);

        GuiManager.applyFooter27(inventory, lang, false);
    }

    @Override
    public @NotNull Inventory getInventory() { return inventory; }
}
