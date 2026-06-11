package me.lovelace.loveAuth.gui;

import me.lovelace.loveAuth.auth.AuthManager;
import me.lovelace.loveAuth.lang.LangManager;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

public final class PasswordGui implements LoveAuthHolder {
    private final Player player;
    private final LangManager lang;
    private final AuthManager auth;
    private Inventory inventory;

    public PasswordGui(Player player, LangManager lang, AuthManager auth) {
        this.player = player;
        this.lang = lang;
        this.auth = auth;
    }

    public void open() {
        this.inventory = Bukkit.createInventory(this, 27, lang.component("gui.password.title"));
        GuiManager.fillBackground(inventory, lang);

        ItemStack back = new ItemStack(Material.ARROW);
        ItemMeta backMeta = back.getItemMeta();
        if (backMeta != null) {
            backMeta.displayName(lang.component("gui.back-button"));
            back.setItemMeta(backMeta);
        }
        inventory.setItem(11, back);

        ItemStack enter = new ItemStack(Material.TRIPWIRE_HOOK);
        ItemMeta enterMeta = enter.getItemMeta();
        if (enterMeta != null) {
            enterMeta.displayName(lang.component("gui.password.enter-button"));
            enterMeta.lore(lang.lore("gui.password.enter-lore"));
            enter.setItemMeta(enterMeta);
        }
        inventory.setItem(13, enter);

        player.openInventory(inventory);
    }

    @Override
    public @NotNull Inventory getInventory() { return inventory; }
}
