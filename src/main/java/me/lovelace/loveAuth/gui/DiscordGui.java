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

        ItemStack back = HeadTextures.createSkull(HeadTextures.HEAD_BACK,
                lang.component("gui.back-button"), java.util.Collections.emptyList());
        inventory.setItem(11, back);

        auth.getPlugin().getDatabaseManager().findPlayer(player.getUniqueId()).thenAccept(record -> {
            boolean hasDiscord = record.map(r -> r.hasDiscord()).orElse(false);
            boolean passEnabled = record.map(r -> r.passwordEnabled()).orElse(true);
            boolean hasWorkingPassword = record.map(r -> r.hasPassword() && r.passwordEnabled()).orElse(false);

            Bukkit.getScheduler().runTask(auth.getPlugin(), () -> {
                ItemStack bind;
                if (!hasDiscord) {
                    bind = HeadTextures.createSkull(HeadTextures.HEAD_DISCORD,
                            lang.component("gui.discord.bind-button"), lang.lore("gui.discord.bind-lore"));
                } else if (!hasWorkingPassword) {
                    bind = HeadTextures.createSkull(HeadTextures.HEAD_INACTIVE,
                            lang.component("gui.discord.unlink-locked"), lang.lore("gui.discord.unlink-locked-lore"));
                } else {
                    bind = HeadTextures.createSkull(HeadTextures.HEAD_DISCORD,
                            lang.component("gui.discord.unlink-button"), lang.lore("gui.discord.unlink-lore"));
                }
                inventory.setItem(13, bind);

                if (!hasDiscord) {
                    ItemStack locked = HeadTextures.createSkull(HeadTextures.HEAD_INACTIVE,
                            lang.component("gui.discord.toggle-locked"), lang.lore("gui.discord.toggle-locked-lore"));
                    inventory.setItem(15, locked);
                } else {
                    ItemStack toggle = HeadTextures.createSkull(HeadTextures.HEAD_CHANGE_PASS,
                            lang.component(passEnabled ? "gui.discord.toggle-password-off" : "gui.discord.toggle-password-on"),
                            lang.lore("gui.discord.toggle-lore"));
                    inventory.setItem(15, toggle);
                }
            });
        });
    }

    @Override
    public @NotNull Inventory getInventory() { return inventory; }
}
