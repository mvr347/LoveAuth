package me.lovelace.loveAuth.gui;

import me.lovelace.loveAuth.auth.AuthManager;
import me.lovelace.loveAuth.auth.AuthMethod;
import me.lovelace.loveAuth.config.ConfigManager;
import me.lovelace.loveAuth.database.DatabaseManager;
import me.lovelace.loveAuth.lang.LangManager;
import me.lovelace.loveAuth.textures.HeadTextures;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Account menu (gui_gen v2.1). Only the actions that are available right now are shown; the
 * remaining buttons are centred by {@link GuiManager#accountLayout(int)} (27 slots for up to three
 * buttons, 36 for four to six). Everything is gathered first (the account record and the session
 * expiry), and only then laid out in one go, so the player never sees a half-filled menu.
 */
public final class AccountGui implements LoveAuthHolder {
    public enum Action { CHANGE_PASSWORD, DELETE_PASSWORD, SESSION, LOGIN_METHOD, DISCORD, LOGOUT, BACK, CLOSE }

    /** The longest session the stepper offers; 0 means the session is off. Shown as the bar's 100 % mark. */
    public static final int MAX_SESSION_DAYS = 28;

    private static final DateTimeFormatter DF = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault());

    private final Player player;
    private final LangManager lang;
    private final AuthManager auth;
    private final ConfigManager config;
    /** true если меню открыто со скрытым аргументом ("/loveauth account &lt;что угодно&gt;") —
     *  т.е. встроено кнопкой в чьё-то DeluxeMenus-меню. Само значение аргумента не используется:
     *  команда возврата и видимость профиля берутся из config.yml (gui.account.*). */
    private final boolean embedded;
    private final SlotActions<Action> actions = new SlotActions<>();
    private volatile Inventory inventory;

    public AccountGui(Player player, LangManager lang, AuthManager auth, ConfigManager config) {
        this(player, lang, auth, config, false);
    }

    public AccountGui(Player player, LangManager lang, AuthManager auth, ConfigManager config, boolean embedded) {
        this.player = player;
        this.lang = lang;
        this.auth = auth;
        this.config = config;
        this.embedded = embedded;
    }

    /**
     * Which control buttons are visible, in display order. Unavailable ones are hidden, not greyed out.
     * Deleting the password needs a password and a Discord to log in with afterwards; the session
     * length belongs to the password login; the login-method switch needs both methods; everything
     * Discord-related disappears when the integration is switched off.
     */
    public static List<Action> visibleActions(boolean hasWorkingPassword, boolean hasDiscord, boolean discordEnabled) {
        List<Action> list = new ArrayList<>(6);
        list.add(Action.CHANGE_PASSWORD);
        if (hasWorkingPassword && hasDiscord && discordEnabled) list.add(Action.DELETE_PASSWORD);
        if (hasWorkingPassword) list.add(Action.SESSION);
        if (hasWorkingPassword && hasDiscord && discordEnabled) list.add(Action.LOGIN_METHOD);
        if (discordEnabled) list.add(Action.DISCORD);
        list.add(Action.LOGOUT);
        return list;
    }

    /** Loads the data, lays the menu out once and opens it. */
    public void open() {
        load(true);
    }

    /** Re-reads the data and redraws; does nothing if the player is no longer looking at this menu. */
    public void refresh() {
        load(false);
    }

    public Optional<Action> actionAt(int slot) {
        return actions.at(slot);
    }

    private record Data(Optional<DatabaseManager.PlayerRecord> record, Optional<Long> sessionExpiry) {}

    private void load(boolean open) {
        auth.getPlugin().getDatabaseManager().findPlayer(player.getUniqueId())
                .thenCompose(record -> {
                    boolean needsSession = record.map(r -> r.hasPassword() && r.passwordEnabled()).orElse(false);
                    if (!needsSession) return CompletableFuture.completedFuture(new Data(record, Optional.empty()));
                    return auth.getSessionManager().getExpiry(player.getUniqueId())
                            .thenApply(expiry -> new Data(record, expiry));
                })
                .thenAccept(data -> Bukkit.getScheduler().runTask(auth.getPlugin(), () -> render(data, open)))
                .exceptionally(ex -> {
                    auth.getPlugin().getLogger().warning("Account menu failed to load for " + player.getName() + ": " + ex);
                    return null;
                });
    }

    /** Main thread only. */
    private void render(Data data, boolean open) {
        if (!player.isOnline()) return;
        Inventory current = inventory;
        if (!open && (current == null || player.getOpenInventory().getTopInventory().getHolder() != this)) return;

        DatabaseManager.PlayerRecord pr = data.record().orElse(null);
        boolean hasWorkingPassword = pr != null && pr.hasPassword() && pr.passwordEnabled();
        boolean hasDiscord = pr != null && pr.hasDiscord();
        List<Action> visible = visibleActions(hasWorkingPassword, hasDiscord, config.isDiscordEnabled());
        GuiManager.Layout layout = GuiManager.accountLayout(visible.size());

        boolean replaced = current == null || current.getSize() != layout.size();
        if (replaced) {
            current = Bukkit.createInventory(this, layout.size(), lang.component("gui.account.title"));
            inventory = current;
        } else {
            GuiManager.clearWorkZone(current);
        }

        GuiManager.applyFrame(current, lang, embedded);
        // Slot 0 (профиль) виден только в embedded-режиме и только если это не отключено в
        // config.yml; иначе слот лежит в застеклённой строке, поэтому там стекло.
        current.setItem(0, embedded && config.isAccountProfileEnabled()
                ? GuiManager.playerHead(player, lang) : GuiManager.glassPane(lang));

        Map<Integer, Action> map = new HashMap<>();
        for (int i = 0; i < visible.size(); i++) {
            Action action = visible.get(i);
            int slot = layout.slots()[i];
            current.setItem(slot, buttonFor(action, pr, data.sessionExpiry()));
            map.put(slot, action);
        }
        map.put(layout.size() - 1, Action.CLOSE);
        if (embedded) map.put(layout.size() - 2, Action.BACK);
        actions.replace(map);

        if (open || replaced) player.openInventory(current);
    }

    private org.bukkit.inventory.ItemStack buttonFor(Action action, DatabaseManager.PlayerRecord pr, Optional<Long> sessionExpiry) {
        boolean hasPassword = pr != null && pr.hasPassword() && pr.passwordEnabled();
        boolean hasDiscord = pr != null && pr.hasDiscord();
        LoreBlocks blocks = new LoreBlocks(lang);
        return switch (action) {
            case CHANGE_PASSWORD -> hasPassword
                    ? item(HeadTextures.HEAD_CHANGE_PASS, "gui.account.change-password", "gui.account.change-password-lore", Map.of(),
                            blocks.clicks(blocks.left("gui.account.change-password-action")))
                    : item(HeadTextures.HEAD_CHANGE_PASS, "gui.account.set-password", "gui.account.set-password-lore", Map.of(),
                            blocks.clicks(blocks.left("gui.account.set-password-action")));
            case DELETE_PASSWORD -> item(HeadTextures.HEAD_BARRIER, "gui.account.delete-password", "gui.account.delete-password-lore", Map.of(),
                    blocks.clicks(blocks.left("gui.account.delete-password-action")));
            case SESSION -> {
                int days = pr.sessionDuration();
                String status = lang.plain(sessionExpiry.isPresent() ? "gui.account.session-active" : "gui.account.session-disabled");
                String expires = sessionExpiry.map(e -> DF.format(Instant.ofEpochSecond(e))).orElse("---");
                yield item(days == 0 ? HeadTextures.HEAD_INACTIVE : HeadTextures.HEAD_SESSION,
                        days == 0 ? "gui.session.disable" : "gui.session.days", "gui.account.session-lore",
                        Map.of("days", String.valueOf(days), "status", status, "expires", expires,
                                "max", String.valueOf(MAX_SESSION_DAYS), "bar", LoreBlocks.bar(days, MAX_SESSION_DAYS)),
                        blocks.clicks(blocks.left("gui.account.session-add-action"), blocks.right("gui.account.session-remove-action")));
            }
            case LOGIN_METHOD -> {
                boolean preferDiscord = pr.preferredAuthMethod() == AuthMethod.DISCORD;
                yield item(preferDiscord ? HeadTextures.HEAD_DISCORD : HeadTextures.HEAD_PASSWORD,
                        "gui.account.login-method", "gui.account.login-method-lore",
                        Map.of("method", lang.plain(preferDiscord ? "gui.account.login-discord" : "gui.account.login-password"),
                                "password-option", option(!preferDiscord, "gui.account.login-password"),
                                "discord-option", option(preferDiscord, "gui.account.login-discord")),
                        blocks.clicks(blocks.left("gui.account.login-method-action")));
            }
            case DISCORD -> item(HeadTextures.HEAD_DISCORD,
                    hasDiscord ? "gui.discord.unlink-button" : "gui.discord.bind-button",
                    hasDiscord ? "gui.discord.unlink-lore" : "gui.discord.bind-lore", Map.of(),
                    blocks.clicks(blocks.left(hasDiscord ? "gui.discord.unlink-action" : "gui.discord.bind-action")));
            case LOGOUT -> item(HeadTextures.HEAD_EXIT_ACCOUNT, "gui.account.logout", "gui.account.logout-lore", Map.of(),
                    blocks.clicks(blocks.left("gui.account.logout-action")));
            default -> throw new IllegalStateException("Not a content button: " + action);
        };
    }

    private org.bukkit.inventory.ItemStack item(String headTexture, String nameKey, String loreKey, Map<String, String> p,
                                                List<Component> block) {
        return GuiManager.button(lang, headTexture, nameKey, loreKey, p, block);
    }

    /** One login-method option line: the current method is marked with ▶, the other one is indented. */
    private String option(boolean current, String nameKey) {
        return lang.get(current ? "gui.account.option-current" : "gui.account.option-other", Map.of("name", lang.plain(nameKey)));
    }

    /** Выполняет команду возврата (gui.account.back-command из config.yml) в embedded-режиме. */
    public void goBack() {
        if (!embedded) return;
        String cmd = config.getAccountBackCommand();
        if (!cmd.isBlank()) player.performCommand(cmd);
    }

    @Override
    public @NotNull Inventory getInventory() { return inventory; }
}
