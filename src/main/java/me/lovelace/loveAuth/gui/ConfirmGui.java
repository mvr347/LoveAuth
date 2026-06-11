package me.lovelace.loveAuth.gui;

import me.lovelace.loveAuth.lang.LangManager;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;

public final class ConfirmGui implements LoveAuthHolder {
    private final Player player;
    private final LangManager lang;
    private final String descriptionKey;
    private final Runnable onConfirm;
    private final Runnable onCancel;
    private Inventory inventory;

    public ConfirmGui(Player player, LangManager lang, String descriptionKey, Runnable onConfirm, Runnable onCancel) {
        this.player = player;
        this.lang = lang;
        this.descriptionKey = descriptionKey;
        this.onConfirm = onConfirm;
        this.onCancel = onCancel;
    }

    public void open() {
        this.inventory = Bukkit.createInventory(this, 27, lang.component("gui.confirm.title"));
        GuiManager.fillBackground(inventory, lang);

        ItemStack info = new ItemStack(Material.PAPER);
        ItemMeta infoMeta = info.getItemMeta();
        if (infoMeta != null) {
            infoMeta.displayName(lang.component("gui.confirm.description-item"));
            infoMeta.lore(Collections.singletonList(lang.component(descriptionKey)));
            info.setItemMeta(infoMeta);
        }
        inventory.setItem(4, info);

        ItemStack confirm = new ItemStack(Material.LIME_CONCRETE);
        ItemMeta confirmMeta = confirm.getItemMeta();
        if (confirmMeta != null) {
            confirmMeta.displayName(lang.component("gui.confirm-button"));
            confirm.setItemMeta(confirmMeta);
        }
        inventory.setItem(12, confirm);

        ItemStack cancel = new ItemStack(Material.RED_CONCRETE);
        ItemMeta cancelMeta = cancel.getItemMeta();
        if (cancelMeta != null) {
            cancelMeta.displayName(lang.component("gui.cancel-button"));
            cancel.setItemMeta(cancelMeta);
        }
        inventory.setItem(14, cancel);

        player.openInventory(inventory);
    }

    public void handleConfirm() {
        player.closeInventory();
        onConfirm.run();
    }

    public void handleCancel() {
        player.closeInventory();
        onCancel.run();
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
