package me.lovelace.loveAuth.gui;

import me.lovelace.loveAuth.lang.LangManager;
import me.lovelace.loveAuth.queue.QueueManager;
import me.lovelace.loveAuth.textures.HeadTextures;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;

public final class QueueGui implements LoveAuthHolder {
    private final Player player;
    private final LangManager lang;
    private final QueueManager queue;
    public enum Action { REFRESH, CLOSE }

    private final SlotActions<Action> actions = new SlotActions<>();
    private Inventory inventory;

    public QueueGui(Player player, LangManager lang, QueueManager queue) {
        this.player = player;
        this.lang = lang;
        this.queue = queue;
    }

    public java.util.Optional<Action> actionAt(int slot) { return actions.at(slot); }

    public void open() {
        this.inventory = Bukkit.createInventory(this, 27, lang.component("gui.queue.title"));
        refresh();
        player.openInventory(inventory);
    }

    public void refresh() {
        GuiManager.clearWorkZone(inventory);
        GuiManager.applyFrame(inventory, lang, false);
        inventory.setItem(0, GuiManager.playerHead(player, lang));

        int pos = queue.getPosition(player.getUniqueId());
        int total = queue.getTotal();
        String time = (pos * 5) + "s";

        LoreBlocks blocks = new LoreBlocks(lang);
        int[] slots = GuiManager.centeredRow(9, 2);
        // The position is information, not an action: no click line, the description carries the sections.
        inventory.setItem(slots[0], GuiManager.button(lang, HeadTextures.HEAD_INFO, "gui.queue.position-item", "gui.queue.position-lore",
                Map.of("position", Integer.toString(pos), "total", Integer.toString(total), "time", time), List.of()));
        inventory.setItem(slots[1], GuiManager.button(lang, HeadTextures.HEAD_BACK, "gui.queue.refresh-button", "gui.queue.refresh-lore",
                blocks.clicks(blocks.left("gui.queue.refresh-action"))));
        actions.replace(Map.of(slots[1], Action.REFRESH, inventory.getSize() - 1, Action.CLOSE));
    }

    @Override
    public @NotNull Inventory getInventory() { return inventory; }
}
