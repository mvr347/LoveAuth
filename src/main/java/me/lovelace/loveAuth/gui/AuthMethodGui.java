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

        ItemStack pass = HeadTextures.createSkull(HeadTextures.HEAD_PASSWORD,
                lang.component(registered ? "gui.auth-method.password-button" : "gui.register.register-button"),
                lang.lore(registered ? "gui.auth-method.password-lore" : "gui.register.register-lore"));
        inventory.setItem(12, pass);

        ItemStack disc = HeadTextures.createSkull(HeadTextures.HEAD_DISCORD,
                lang.component("gui.auth-method.discord-button"),
                lang.lore(config.isDiscordEnabled() ? "gui.auth-method.discord-lore" : "gui.auth-method.discord-disabled-lore"));
        inventory.setItem(14, disc);

        player.openInventory(inventory);

        if (registered) {
            auth.getPlugin().getDatabaseManager().findPlayer(player.getUniqueId()).thenAccept(record -> {
                boolean hasDiscord = record.map(r -> r.hasDiscord()).orElse(false);
                boolean hasPassword = record.map(r -> r.hasPassword() && r.passwordEnabled()).orElse(false);
                Bukkit.getScheduler().runTask(auth.getPlugin(), () -> {
                    if (!player.isOnline()) return;
                    if (!hasPassword) {
                        ItemStack locked = HeadTextures.createSkull(HeadTextures.HEAD_INACTIVE,
                                lang.component("gui.auth-method.password-locked"),
                                lang.lore("gui.auth-method.password-locked-lore"));
                        inventory.setItem(12, locked);
                    }
                    if (config.isDiscordEnabled() && !hasDiscord) {
                        ItemStack bindDisc = HeadTextures.createSkull(HeadTextures.HEAD_DISCORD,
                                lang.component("gui.auth-method.discord-bind-button"),
                                lang.lore("gui.auth-method.discord-bind-lore"));
                        inventory.setItem(14, bindDisc);
                    }
                });
            });
        }
    }

    @Override
    public @NotNull Inventory getInventory() { return inventory; }
}
