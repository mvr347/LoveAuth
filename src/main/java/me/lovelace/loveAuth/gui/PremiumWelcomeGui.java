package me.lovelace.loveAuth.gui;

import me.lovelace.loveAuth.auth.AuthManager;
import me.lovelace.loveAuth.config.ConfigManager;
import me.lovelace.loveAuth.discord.DiscordAuthManager;
import me.lovelace.loveAuth.lang.LangManager;
import me.lovelace.loveAuth.textures.HeadTextures;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
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

        LoreBlocks blocks = new LoreBlocks(lang);
        inventory.setItem(slots[0], GuiManager.button(lang, HeadTextures.HEAD_PASSWORD, "gui.premium-welcome.add-password-button",
                "gui.premium-welcome.add-password-lore", blocks.clicks(blocks.left("gui.premium-welcome.add-password-action"))));

        if (discordEnabled) {
            inventory.setItem(slots[1], GuiManager.button(lang, HeadTextures.HEAD_DISCORD, "gui.premium-welcome.bind-discord-button",
                    "gui.premium-welcome.bind-discord-lore", blocks.clicks(blocks.left("gui.premium-welcome.bind-discord-action"))));
        }

        inventory.setItem(slots[slots.length - 1], GuiManager.button(lang, HeadTextures.HEAD_BACK, "gui.premium-welcome.skip-button",
                "gui.premium-welcome.skip-lore", blocks.clicks(blocks.left("gui.premium-welcome.skip-action"))));

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
