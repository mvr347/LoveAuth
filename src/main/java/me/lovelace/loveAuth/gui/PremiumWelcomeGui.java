package me.lovelace.loveAuth.gui;

import me.lovelace.loveAuth.auth.AuthManager;
import me.lovelace.loveAuth.config.ConfigManager;
import me.lovelace.loveAuth.discord.DiscordAuthManager;
import me.lovelace.loveAuth.lang.LangManager;
import me.lovelace.loveAuth.util.HeadTextures;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
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

        ItemStack password = HeadTextures.createSkull(HeadTextures.HEAD_PASSWORD,
                lang.component("gui.premium-welcome.add-password-button"), lang.lore("gui.premium-welcome.add-password-lore"));
        inventory.setItem(11, password);

        if (config.isDiscordEnabled()) {
            ItemStack discordItem = HeadTextures.createSkull(HeadTextures.HEAD_DISCORD,
                    lang.component("gui.premium-welcome.bind-discord-button"), lang.lore("gui.premium-welcome.bind-discord-lore"));
            inventory.setItem(13, discordItem);
        }

        ItemStack skip = HeadTextures.createSkull(HeadTextures.HEAD_BACK,
                lang.component("gui.premium-welcome.skip-button"), lang.lore("gui.premium-welcome.skip-lore"));
        inventory.setItem(15, skip);

        player.openInventory(inventory);
    }

    @Override
    public @NotNull Inventory getInventory() { return inventory; }
}
