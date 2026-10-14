package me.lovelace.loveAuth.gui;

import me.lovelace.loveAuth.auth.AuthManager;
import me.lovelace.loveAuth.config.ConfigManager;
import me.lovelace.loveAuth.discord.DiscordAuthManager;
import me.lovelace.loveAuth.lang.LangManager;
import me.lovelace.loveAuth.textures.HeadTextures;
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
        GuiManager.standardFrame27(inventory, lang);
        inventory.setItem(0, GuiManager.playerHead(player, lang));

        boolean discordEnabled = config.isDiscordEnabled();
        int[] slots = slots(discordEnabled);

        ItemStack password = HeadTextures.createSkull(HeadTextures.HEAD_PASSWORD,
                lang.component("gui.premium-welcome.add-password-button"), lang.lore("gui.premium-welcome.add-password-lore"));
        inventory.setItem(slots[0], password);

        if (discordEnabled) {
            ItemStack discordItem = HeadTextures.createSkull(HeadTextures.HEAD_DISCORD,
                    lang.component("gui.premium-welcome.bind-discord-button"), lang.lore("gui.premium-welcome.bind-discord-lore"));
            inventory.setItem(slots[1], discordItem);
        }

        ItemStack skip = HeadTextures.createSkull(HeadTextures.HEAD_BACK,
                lang.component("gui.premium-welcome.skip-button"), lang.lore("gui.premium-welcome.skip-lore"));
        inventory.setItem(slots[slots.length - 1], skip);

        GuiManager.applyFooter27(inventory, lang, false);

        player.openInventory(inventory);
    }

    /** Password first, Discord in the middle when enabled, "skip" last - always centred. */
    public static int[] slots(boolean discordEnabled) {
        return GuiManager.centeredRow(9, discordEnabled ? 3 : 2);
    }

    @Override
    public @NotNull Inventory getInventory() { return inventory; }
}
