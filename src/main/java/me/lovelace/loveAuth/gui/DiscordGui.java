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
        refresh();
        player.openInventory(inventory);
    }

    public void refresh() {
        GuiManager.fillBackground(inventory, lang);

        ItemStack back = new ItemStack(Material.ARROW);
        ItemMeta backMeta = back.getItemMeta();
        if (backMeta != null) {
            backMeta.displayName(lang.component("gui.back-button"));
            back.setItemMeta(backMeta);
        }
        inventory.setItem(11, back);

        auth.getPlugin().getDatabaseManager().findPlayer(player.getUniqueId()).thenAccept(record -> {
            boolean hasDiscord = record.map(r -> r.hasDiscord()).orElse(false);
            boolean passEnabled = record.map(r -> r.passwordEnabled()).orElse(true);

            Bukkit.getScheduler().runTask(auth.getPlugin(), () -> {
                ItemStack bind = new ItemStack(hasDiscord ? Material.CHAINMAIL_CHESTPLATE : Material.CHAIN);
                ItemMeta bindMeta = bind.getItemMeta();
                if (bindMeta != null) {
                    bindMeta.displayName(lang.component(hasDiscord ? "gui.discord.unlink-button" : "gui.discord.bind-button"));
                    bindMeta.lore(lang.lore(hasDiscord ? "gui.discord.unlink-lore" : "gui.discord.bind-lore"));
                    bind.setItemMeta(bindMeta);
                }
                inventory.setItem(13, bind);

                ItemStack toggle = new ItemStack(passEnabled ? Material.LEVER : Material.REDSTONE_TORCH);
                ItemMeta toggleMeta = toggle.getItemMeta();
                if (toggleMeta != null) {
                    toggleMeta.displayName(lang.component(passEnabled ? "gui.discord.toggle-password-off" : "gui.discord.toggle-password-on"));
                    toggleMeta.lore(lang.lore("gui.discord.toggle-lore"));
                    toggle.setItemMeta(toggleMeta);
                }
                inventory.setItem(15, toggle);
            });
        });
    }

    @Override
    public @NotNull Inventory getInventory() { return inventory; }
}
