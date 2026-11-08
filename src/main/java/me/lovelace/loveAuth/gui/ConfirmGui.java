package me.lovelace.loveAuth.gui;

import me.lovelace.loveAuth.lang.LangManager;
import me.lovelace.loveAuth.textures.HeadTextures;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.jetbrains.annotations.NotNull;

public final class ConfirmGui implements LoveAuthHolder {
    public static final int CONFIRM_SLOT = 1;
    public static final int CANCEL_SLOT = 3;

    private final Player player;
    private final LangManager lang;
    private final String descriptionKey;
    private final Runnable onConfirm;
    private final Runnable onCancel;
    private Inventory inventory;
    /** True when the big ItemsAdder window is open (buttons are zones, not single slots). */
    private boolean zoned;

    public ConfirmGui(Player player, LangManager lang, String descriptionKey, Runnable onConfirm, Runnable onCancel) {
        this.player = player;
        this.lang = lang;
        this.descriptionKey = descriptionKey;
        this.onConfirm = onConfirm;
        this.onCancel = onCancel;
    }

    public void open() {
        LoreBlocks blocks = new LoreBlocks(lang);
        var confirm = GuiManager.button(lang, HeadTextures.HEAD_CHECK, "gui.confirm-button", "gui.confirm-lore",
                blocks.clicks(blocks.left("gui.confirm-action")));
        var cancel = GuiManager.button(lang, HeadTextures.HEAD_X, "gui.cancel-button", "gui.cancel-lore",
                blocks.clicks(blocks.left("gui.cancel-action")));

        // The server's big confirm menu (same picture and zones as in LoveShop / LoveClans) when it can be shown.
        String why = AuthMenuLayout.reasonFor(player, AuthMenuLayout.Menu.CONFIRM, true);
        AuthMenuLayout.report(AuthMenuLayout.Menu.CONFIRM, why);
        if (why == null) {
            var question = lang.component(descriptionKey);
            var bigConfirm = AuthMenuLayout.button(withQuestion(confirm, question));
            var bigCancel = AuthMenuLayout.button(cancel);
            if (bigConfirm != null && bigCancel != null) {
                this.zoned = true;
                this.inventory = Bukkit.createInventory(this, AuthMenuLayout.SIZE, AuthMenuLayout.title(player, AuthMenuLayout.Menu.CONFIRM));
                AuthMenuLayout.fill(inventory, AuthMenuLayout.Menu.CONFIRM,
                        java.util.Map.of(AuthMenuLayout.Zone.CONFIRM, bigConfirm, AuthMenuLayout.Zone.CANCEL, bigCancel));
                player.openInventory(inventory);
                return;
            }
        }

        // Хоппер (5 слотов) gui_gen-стандарт: стекло, подтвердить, стекло, отменить, стекло.
        // Сам вопрос ("Изменить пароль?" и т.п.) выводится в заголовке — отдельного
        // инфо-слота под него не отводим.
        this.zoned = false;
        this.inventory = Bukkit.createInventory(this, InventoryType.HOPPER, lang.component(descriptionKey));
        GuiManager.fillBackground(inventory, lang);
        inventory.setItem(CONFIRM_SLOT, confirm);
        inventory.setItem(CANCEL_SLOT, cancel);
        player.openInventory(inventory);
    }

    /** The question has no title in the big window, so it leads the lore of the confirm button. */
    private static org.bukkit.inventory.ItemStack withQuestion(org.bukkit.inventory.ItemStack button, net.kyori.adventure.text.Component question) {
        var copy = button.clone();
        copy.editMeta(meta -> {
            var lore = new java.util.ArrayList<net.kyori.adventure.text.Component>();
            lore.add(question);
            lore.add(net.kyori.adventure.text.Component.empty());
            if (meta.lore() != null) lore.addAll(meta.lore());
            meta.lore(lore);
        });
        return copy;
    }

    /** What a slot does: confirm, cancel or nothing. */
    public java.util.Optional<AuthMenuLayout.Zone> actionAt(int slot) {
        if (zoned) return AuthMenuLayout.zoneOf(AuthMenuLayout.Menu.CONFIRM, slot);
        if (slot == CONFIRM_SLOT) return java.util.Optional.of(AuthMenuLayout.Zone.CONFIRM);
        if (slot == CANCEL_SLOT) return java.util.Optional.of(AuthMenuLayout.Zone.CANCEL);
        return java.util.Optional.empty();
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
