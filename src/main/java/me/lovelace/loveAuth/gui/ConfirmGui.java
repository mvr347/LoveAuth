package me.lovelace.loveAuth.gui;

import me.lovelace.loveAuth.lang.LangManager;
import me.lovelace.loveAuth.util.HeadTextures;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;

public final class ConfirmGui implements LoveAuthHolder {
    public static final int CONFIRM_SLOT = 1;
    public static final int CANCEL_SLOT = 3;

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
        // Хоппер (5 слотов) gui_gen-стандарт: стекло, подтвердить, стекло, отменить, стекло.
        // Сам вопрос ("Изменить пароль?" и т.п.) выводится в заголовке — отдельного
        // инфо-слота под него не отводим.
        this.inventory = Bukkit.createInventory(this, InventoryType.HOPPER, lang.component(descriptionKey));
        GuiManager.fillBackground(inventory, lang);

        ItemStack confirm = HeadTextures.createSkull(HeadTextures.HEAD_CHECK,
                lang.component("gui.confirm-button"), Collections.emptyList());
        inventory.setItem(CONFIRM_SLOT, confirm);

        ItemStack cancel = HeadTextures.createSkull(HeadTextures.HEAD_X,
                lang.component("gui.cancel-button"), Collections.emptyList());
        inventory.setItem(CANCEL_SLOT, cancel);

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
