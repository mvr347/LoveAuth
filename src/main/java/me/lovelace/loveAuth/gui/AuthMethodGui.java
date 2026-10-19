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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Login method choice (27 slots, gui_gen v2.1). The account state is read first and the buttons are
 * laid out once, so the player never sees a button flip into another one a moment later. A method
 * that cannot be used is hidden and the rest are centred.
 */
public final class AuthMethodGui implements LoveAuthHolder {
    public enum Action { PASSWORD, DISCORD, CLOSE }

    private static final int SIZE = 27;

    private final Player player;
    private final LangManager lang;
    private final ConfigManager config;
    private final AuthManager auth;
    private final DiscordAuthManager discord;
    private final SlotActions<Action> actions = new SlotActions<>();
    private volatile Inventory inventory;

    public AuthMethodGui(Player player, LangManager lang, ConfigManager config, AuthManager auth, DiscordAuthManager discord) {
        this.player = player;
        this.lang = lang;
        this.config = config;
        this.auth = auth;
        this.discord = discord;
    }

    public Optional<Action> actionAt(int slot) {
        return actions.at(slot);
    }

    public void open() {
        if (!auth.isRegisteredCached(player.getUniqueId())) {
            render(false, false, false);
            return;
        }
        auth.getPlugin().getDatabaseManager().findPlayer(player.getUniqueId()).thenAccept(record -> {
            boolean hasDiscord = record.map(r -> r.hasDiscord()).orElse(false);
            boolean hasPassword = record.map(r -> r.hasPassword() && r.passwordEnabled()).orElse(false);
            Bukkit.getScheduler().runTask(auth.getPlugin(), () -> {
                // The login menu is only for players who are not through yet.
                if (!player.isOnline() || auth.isAuthenticated(player.getUniqueId())) return;
                render(true, hasPassword, hasDiscord);
            });
        }).exceptionally(ex -> {
            auth.getPlugin().getLogger().warning("Login method menu failed to load for " + player.getName() + ": " + ex);
            return null;
        });
    }

    /** Main thread only. */
    private void render(boolean registered, boolean hasPassword, boolean hasDiscord) {
        Inventory inv = Bukkit.createInventory(this, SIZE, lang.component("gui.auth-method.title"));
        inventory = inv;
        GuiManager.applyFrame(inv, lang, false);
        inv.setItem(0, GuiManager.playerHead(player, lang));

        LoreBlocks blocks = new LoreBlocks(lang);
        boolean discordEnabled = config.isDiscordEnabled();
        List<Action> visible = new ArrayList<>(2);
        List<ItemStack> items = new ArrayList<>(2);

        if (!registered || hasPassword) {
            visible.add(Action.PASSWORD);
            items.add(registered
                    ? GuiManager.button(lang, HeadTextures.HEAD_PASSWORD, "gui.auth-method.password-button",
                            "gui.auth-method.password-lore", blocks.clicks(blocks.left("gui.auth-method.password-action")))
                    : GuiManager.button(lang, HeadTextures.HEAD_PASSWORD, "gui.register.register-button",
                            "gui.register.register-lore", blocks.clicks(blocks.left("gui.register.register-action"))));
        }
        if (discordEnabled) {
            visible.add(Action.DISCORD);
            boolean bind = registered && !hasDiscord;
            items.add(bind
                    ? GuiManager.button(lang, HeadTextures.HEAD_DISCORD, "gui.auth-method.discord-bind-button",
                            "gui.auth-method.discord-bind-lore", blocks.clicks(blocks.left("gui.auth-method.discord-bind-action")))
                    : GuiManager.button(lang, HeadTextures.HEAD_DISCORD, "gui.auth-method.discord-button",
                            "gui.auth-method.discord-lore", blocks.clicks(blocks.left("gui.auth-method.discord-action"))));
        }
        if (visible.isEmpty()) {
            // A registered account with neither a working password nor Discord: keep the explanatory
            // button rather than an empty menu - its click tells the player why the password is off.
            visible.add(Action.PASSWORD);
            items.add(GuiManager.button(lang, HeadTextures.HEAD_INACTIVE, "gui.auth-method.password-locked",
                    "gui.auth-method.password-locked-lore",
                    blocks.blocked("gui.auth-method.password-locked-reason",
                            blocks.left("gui.auth-method.password-locked-action"))));
        }

        Map<Integer, Action> map = new HashMap<>();
        int[] slots = GuiManager.centeredRow(9, visible.size());
        for (int i = 0; i < slots.length; i++) {
            inv.setItem(slots[i], items.get(i));
            map.put(slots[i], visible.get(i));
        }
        map.put(SIZE - 1, Action.CLOSE);
        actions.replace(map);

        player.openInventory(inv);
    }

    @Override
    public @NotNull Inventory getInventory() { return inventory; }
}
