package me.lovelace.loveAuth.gui;

import me.lovelace.loveAuth.auth.AuthManager;
import me.lovelace.loveAuth.lang.LangManager;
import me.lovelace.loveAuth.textures.HeadTextures;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.jetbrains.annotations.NotNull;

public final class RegisterGui implements LoveAuthHolder {
    private final Player player;
    private final LangManager lang;
    private final AuthManager auth;
    private Inventory inventory;

    public RegisterGui(Player player, LangManager lang, AuthManager auth) {
        this.player = player;
        this.lang = lang;
        this.auth = auth;
    }

    public void open() {
        this.inventory = Bukkit.createInventory(this, 27, lang.component("gui.register.title"));
        GuiManager.standardFrame27(inventory, lang);
        inventory.setItem(0, GuiManager.playerHead(player, lang));

        boolean discordEnabled = auth.getPlugin().getConfigManager().isDiscordEnabled();
        int[] slots = slots(discordEnabled);

        LoreBlocks blocks = new LoreBlocks(lang);
        inventory.setItem(slots[0], GuiManager.button(lang, HeadTextures.HEAD_PASSWORD, "gui.register.register-button",
                "gui.register.register-lore", blocks.clicks(blocks.left("gui.register.register-action"))));

        if (discordEnabled) {
            inventory.setItem(slots[1], GuiManager.button(lang, HeadTextures.HEAD_DISCORD, "gui.discord.bind-button",
                    "gui.discord.bind-lore", blocks.clicks(blocks.left("gui.discord.bind-action"))));
        }

        GuiManager.applyFooter27(inventory, lang, false);

        player.openInventory(inventory);
    }

    /** Button slots: the register button first, then Discord when it is enabled - always centred. */
    public static int[] slots(boolean discordEnabled) {
        return GuiManager.centeredRow(9, discordEnabled ? 2 : 1);
    }

    @Override
    public @NotNull Inventory getInventory() { return inventory; }
}
