package me.lovelace.loveAuth.gui;

import me.lovelace.loveAuth.auth.AuthManager;
import me.lovelace.loveAuth.lang.LangManager;
import me.lovelace.loveAuth.util.HeadTextures;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;

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

        ItemStack back = HeadTextures.createSkull(HeadTextures.HEAD_BACK,
                lang.component("gui.back-button"), Collections.emptyList());
        inventory.setItem(11, back);

        ItemStack enter = HeadTextures.createSkull(HeadTextures.HEAD_PASSWORD,
                lang.component("gui.password.enter-button"), lang.lore("gui.password.enter-lore"));
        inventory.setItem(13, enter);

        player.openInventory(inventory);
    }

    @Override
    public @NotNull Inventory getInventory() { return inventory; }
}
