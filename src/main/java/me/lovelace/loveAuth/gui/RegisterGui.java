package me.lovelace.loveAuth.gui;

import me.lovelace.loveAuth.auth.AuthManager;
import me.lovelace.loveAuth.lang.LangManager;
import me.lovelace.loveAuth.util.HeadTextures;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
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

        ItemStack register = HeadTextures.createSkull(HeadTextures.HEAD_PASSWORD,
                lang.component("gui.register.register-button"), lang.lore("gui.register.register-lore"));
        inventory.setItem(12, register);

        if (auth.getPlugin().getConfigManager().isDiscordEnabled()) {
            ItemStack discord = HeadTextures.createSkull(HeadTextures.HEAD_DISCORD,
                    lang.component("gui.discord.bind-button"), lang.lore("gui.discord.bind-lore"));
            inventory.setItem(14, discord);
        }

        GuiManager.applyFooter27(inventory, lang, false);

        player.openInventory(inventory);
    }

    @Override
    public @NotNull Inventory getInventory() { return inventory; }
}
