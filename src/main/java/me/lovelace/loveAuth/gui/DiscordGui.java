package me.lovelace.loveAuth.gui;

import me.lovelace.loveAuth.auth.AuthManager;
import me.lovelace.loveAuth.config.ConfigManager;
import me.lovelace.loveAuth.discord.DiscordAuthManager;
import me.lovelace.loveAuth.lang.LangManager;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

public final class DiscordGui implements LoveAuthHolder {
    private final Player player;
    private final LangManager lang;
    private final ConfigManager config;
    private final DiscordAuthManager discord;
    private final AuthManager auth;
    private Inventory inventory;

    public DiscordGui(Player player, LangManager lang, ConfigManager config, DiscordAuthManager discord, AuthManager auth) {
        this.player = player;
        this.lang = lang;
        this.config = config;
        this.discord = discord;
        this.auth = auth;
    }

    public void open() {
        this.inventory = Bukkit.createInventory(this, 27, lang.component("gui.discord.title"));
        GuiManager.fillBackground(inventory, lang);

        ItemStack back = new ItemStack(Material.ARROW);
        ItemMeta backMeta = back.getItemMeta();
        if (backMeta != null) {
            backMeta.displayName(lang.component("gui.back-button"));
            back.setItemMeta(backMeta);
        }
        inventory.setItem(11, back);

        ItemStack bind = new ItemStack(Material.CHAIN);
        ItemMeta bindMeta = bind.getItemMeta();
        if (bindMeta != null) {
            bindMeta.displayName(lang.component("gui.discord.bind-button"));
            bindMeta.lore(lang.lore("gui.discord.bind-lore"));
            bind.setItemMeta(bindMeta);
        }
        inventory.setItem(13, bind);

        auth.isAuthenticated(player.getUniqueId());
        ItemStack toggle = new ItemStack(Material.LEVER);
        ItemMeta toggleMeta = toggle.getItemMeta();
        if (toggleMeta != null) {
            toggleMeta.displayName(lang.component("gui.discord.toggle-password-on"));
            toggleMeta.lore(lang.lore("gui.discord.toggle-lore"));
            toggle.setItemMeta(toggleMeta);
        }
        inventory.setItem(15, toggle);

        player.openInventory(inventory);
    }

    @Override
    public @NotNull Inventory getInventory() { return inventory; }
}
