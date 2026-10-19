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

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Discord settings (27 slots, gui_gen v2.1). Unavailable buttons are hidden and the rest are centred
 * in the work row. The menu cannot be opened while the Discord integration is switched off.
 */
public final class DiscordGui implements LoveAuthHolder {
    public enum Action { BIND_OR_UNLINK, TOGGLE_PASSWORD, BACK, CLOSE }

    private static final int SIZE = 27;

    private final Player player;
    private final LangManager lang;
    private final ConfigManager config;
    private final DiscordAuthManager discord;
    private final AuthManager auth;
    private final SlotActions<Action> actions = new SlotActions<>();
    private volatile Inventory inventory;

    public DiscordGui(Player player, LangManager lang, ConfigManager config, DiscordAuthManager discord, AuthManager auth) {
        this.player = player;
        this.lang = lang;
        this.config = config;
        this.discord = discord;
        this.auth = auth;
    }

    public Optional<Action> actionAt(int slot) {
        return actions.at(slot);
    }

    public void open() {
        if (!config.isDiscordEnabled()) return;
        load(true);
    }

    public void refresh() {
        load(false);
    }

    private void load(boolean open) {
        auth.getPlugin().getDatabaseManager().findPlayer(player.getUniqueId()).thenAccept(record -> {
            boolean hasDiscord = record.map(r -> r.hasDiscord()).orElse(false);
            boolean passEnabled = record.map(r -> r.passwordEnabled()).orElse(true);
            boolean hasWorkingPassword = record.map(r -> r.hasPassword() && r.passwordEnabled()).orElse(false);
            Bukkit.getScheduler().runTask(auth.getPlugin(), () -> render(open, hasDiscord, passEnabled, hasWorkingPassword));
        }).exceptionally(ex -> {
            auth.getPlugin().getLogger().warning("Discord menu failed to load for " + player.getName() + ": " + ex);
            return null;
        });
    }

    /** Main thread only. */
    private void render(boolean open, boolean hasDiscord, boolean passEnabled, boolean hasWorkingPassword) {
        if (!player.isOnline() || !config.isDiscordEnabled()) return;
        Inventory current = inventory;
        if (!open && (current == null || player.getOpenInventory().getTopInventory().getHolder() != this)) return;
        if (current == null) {
            current = Bukkit.createInventory(this, SIZE, lang.component("gui.discord.title"));
            inventory = current;
        } else {
            GuiManager.clearWorkZone(current);
        }
        GuiManager.applyFrame(current, lang, true);
        current.setItem(0, GuiManager.playerHead(player, lang));

        // Unlinking needs a working password to fall back on; the password switch needs a linked
        // Discord. Whatever is not possible is simply not shown.
        LoreBlocks blocks = new LoreBlocks(lang);
        java.util.List<Action> visible = new java.util.ArrayList<>(2);
        java.util.List<ItemStack> items = new java.util.ArrayList<>(2);
        if (!hasDiscord) {
            visible.add(Action.BIND_OR_UNLINK);
            items.add(GuiManager.button(lang, HeadTextures.HEAD_DISCORD, "gui.discord.bind-button", "gui.discord.bind-lore",
                    blocks.clicks(blocks.left("gui.discord.bind-action"))));
        } else {
            if (hasWorkingPassword) {
                visible.add(Action.BIND_OR_UNLINK);
                items.add(GuiManager.button(lang, HeadTextures.HEAD_DISCORD, "gui.discord.unlink-button", "gui.discord.unlink-lore",
                        blocks.clicks(blocks.left("gui.discord.unlink-action"))));
            }
            // The name shows the current state; the lore shows what the click changes it to.
            visible.add(Action.TOGGLE_PASSWORD);
            Map<String, String> change = Map.of(
                    "now", lang.get(passEnabled ? "gui.discord.state-on" : "gui.discord.state-off"),
                    "next", lang.get(passEnabled ? "gui.discord.state-off" : "gui.discord.state-on"));
            items.add(GuiManager.button(lang, HeadTextures.HEAD_CHANGE_PASS,
                    passEnabled ? "gui.discord.toggle-password-on" : "gui.discord.toggle-password-off",
                    "gui.discord.toggle-lore", change,
                    blocks.clicks(blocks.left(passEnabled ? "gui.discord.toggle-disable-action" : "gui.discord.toggle-enable-action"))));
        }

        Map<Integer, Action> map = new HashMap<>();
        int[] slots = GuiManager.centeredRow(9, visible.size());
        for (int i = 0; i < slots.length; i++) {
            current.setItem(slots[i], items.get(i));
            map.put(slots[i], visible.get(i));
        }
        map.put(SIZE - 2, Action.BACK);
        map.put(SIZE - 1, Action.CLOSE);
        actions.replace(map);

        if (open) player.openInventory(current);
    }

    @Override
    public @NotNull Inventory getInventory() { return inventory; }
}
