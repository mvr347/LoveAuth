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
        GuiManager.fillBackground(inventory, lang);

        ItemStack register = new ItemStack(Material.WRITABLE_BOOK);
        ItemMeta registerMeta = register.getItemMeta();
        if (registerMeta != null) {
            registerMeta.displayName(lang.component("gui.register.register-button"));
            registerMeta.lore(lang.lore("gui.register.register-lore"));
            register.setItemMeta(registerMeta);
        }
        inventory.setItem(11, register);
        
        if (auth.getPlugin().getConfigManager().isDiscordEnabled()) {
            ItemStack discord = new ItemStack(Material.BLUE_STAINED_GLASS);
            ItemMeta discordMeta = discord.getItemMeta();
            if (discordMeta != null) {
                discordMeta.displayName(lang.component("gui.discord.bind-button"));
                discordMeta.lore(lang.lore("gui.discord.bind-lore"));
                discord.setItemMeta(discordMeta);
            }
            inventory.setItem(15, discord);
        }

        player.openInventory(inventory);
    }

    @Override
    public @NotNull Inventory getInventory() { return inventory; }
}
