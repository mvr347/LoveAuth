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

public final class PremiumWelcomeGui implements LoveAuthHolder {
    private final Player player;
    private final LangManager lang;
    private final ConfigManager config;
    private final DiscordAuthManager discord;
    private final AuthManager auth;
    private Inventory inventory;

    public PremiumWelcomeGui(Player player, LangManager lang, ConfigManager config, DiscordAuthManager discord, AuthManager auth) {
        this.player = player;
        this.lang = lang;
        this.config = config;
        this.discord = discord;
        this.auth = auth;
    }

    public void open() {
        this.inventory = Bukkit.createInventory(this, 27, lang.component("gui.premium-welcome.title"));
        GuiManager.fillBackground(inventory, lang);

        ItemStack password = new ItemStack(Material.TRIPWIRE_HOOK);
        ItemMeta passwordMeta = password.getItemMeta();
        if (passwordMeta != null) {
            passwordMeta.displayName(lang.component("gui.premium-welcome.add-password-button"));
            passwordMeta.lore(lang.lore("gui.premium-welcome.add-password-lore"));
            password.setItemMeta(passwordMeta);
        }
        inventory.setItem(11, password);

        if (config.isDiscordEnabled()) {
            ItemStack discordItem = new ItemStack(Material.BLUE_STAINED_GLASS);
            ItemMeta discordMeta = discordItem.getItemMeta();
            if (discordMeta != null) {
                discordMeta.displayName(lang.component("gui.premium-welcome.bind-discord-button"));
                discordMeta.lore(lang.lore("gui.premium-welcome.bind-discord-lore"));
                discordItem.setItemMeta(discordMeta);
            }
            inventory.setItem(13, discordItem);
        }

        ItemStack skip = new ItemStack(Material.ARROW);
        ItemMeta skipMeta = skip.getItemMeta();
        if (skipMeta != null) {
            skipMeta.displayName(lang.component("gui.premium-welcome.skip-button"));
            skipMeta.lore(lang.lore("gui.premium-welcome.skip-lore"));
            skip.setItemMeta(skipMeta);
        }
        inventory.setItem(15, skip);

        player.openInventory(inventory);
    }

    @Override
    public @NotNull Inventory getInventory() { return inventory; }
}
