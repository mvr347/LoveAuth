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

public final class AuthMethodGui implements LoveAuthHolder {
    private final Player player;
    private final LangManager lang;
    private final ConfigManager config;
    private final AuthManager auth;
    private final DiscordAuthManager discord;
    private Inventory inventory;

    public AuthMethodGui(Player player, LangManager lang, ConfigManager config, AuthManager auth, DiscordAuthManager discord) {
        this.player = player;
        this.lang = lang;
        this.config = config;
        this.auth = auth;
        this.discord = discord;
    }

    public void open() {
        this.inventory = Bukkit.createInventory(this, 27, lang.component("gui.auth-method.title"));
        GuiManager.fillBackground(inventory, lang);

        boolean registered = auth.isRegisteredCached(player.getUniqueId());

        ItemStack pass = new ItemStack(Material.EMERALD);
        ItemMeta pMeta = pass.getItemMeta();
        if (pMeta != null) {
            pMeta.displayName(lang.component(registered ? "gui.auth-method.password-button" : "gui.register.register-button"));
            pMeta.lore(lang.lore(registered ? "gui.auth-method.password-lore" : "gui.register.register-lore"));
            pass.setItemMeta(pMeta);
        }
        inventory.setItem(12, pass);

        ItemStack disc = new ItemStack(Material.BLUE_STAINED_GLASS);
        ItemMeta dMeta = disc.getItemMeta();
        if (dMeta != null) {
            dMeta.displayName(lang.component("gui.auth-method.discord-button"));
            if (config.isDiscordEnabled()) dMeta.lore(lang.lore("gui.auth-method.discord-lore"));
            else dMeta.lore(lang.lore("gui.auth-method.discord-disabled-lore"));
            disc.setItemMeta(dMeta);
        }
        inventory.setItem(14, disc);

        player.openInventory(inventory);
    }

    @Override
    public @NotNull Inventory getInventory() { return inventory; }
}
