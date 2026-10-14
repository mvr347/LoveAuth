package me.lovelace.loveAuth.gui;

import me.lovelace.loveAuth.auth.AuthManager;
import me.lovelace.loveAuth.auth.AuthMethod;
import me.lovelace.loveAuth.config.ConfigManager;
import me.lovelace.loveAuth.database.DatabaseManager;
import me.lovelace.loveAuth.lang.LangManager;
import me.lovelace.loveAuth.textures.HeadTextures;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

public final class AccountGui implements LoveAuthHolder {
    // 36 slots: header, two work rows of three centred buttons, footer (gui_gen v2.1).
    //   row 1 (9-17):  password | delete password | session
    //   row 2 (18-26): login method | Discord | log out
    // Every button is always present (an unavailable one is a grey button with its reason), so the
    // grid never changes shape depending on the account state.
    private static final int SIZE = 36;
    public static final int SLOT_CHANGE_PASSWORD = 11;
    public static final int SLOT_DELETE_PASSWORD = 13;
    public static final int SLOT_SESSION = 15;
    public static final int SLOT_LOGIN_METHOD = 20;
    public static final int SLOT_DISCORD = 22;
    public static final int SLOT_LOGOUT = 24;
    public static final int BACK_SLOT = SIZE - 2;
    public static final int CLOSE_SLOT = SIZE - 1;

    private final Player player;
    private final LangManager lang;
    private final AuthManager auth;
    private final ConfigManager config;
    /** true если меню открыто со скрытым аргументом ("/loveauth account &lt;что угодно&gt;") —
     *  т.е. встроено кнопкой в чьё-то DeluxeMenus-меню. Само значение аргумента не используется:
     *  команда возврата и видимость профиля берутся из config.yml (gui.account.*). */
    private final boolean embedded;
    private Inventory inventory;
    private static final DateTimeFormatter DF = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault());

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

    public void open() {
        this.inventory = Bukkit.createInventory(this, SIZE, lang.component("gui.account.title"));
        refresh();
        player.openInventory(inventory);
    }

    public void refresh() {
        GuiManager.standardFrame36(inventory, lang);
        // Слот 0 (профиль) и кнопка "Назад" видны только в embedded-режиме (открыто со
        // скрытым аргументом — меню встроено кнопкой в чьё-то DeluxeMenus-меню), и только
        // если это не отключено в config.yml. Обычный "/loveauth account" остаётся
        // самостоятельным экраном без профиля и без кнопки "Назад" — но слот 0 лежит в
        // застеклённой строке 0-8 (не в рабочей зоне), поэтому вместо головы там стекло.
        if (embedded && config.isAccountProfileEnabled()) {
            inventory.setItem(0, GuiManager.playerHead(player, lang));
        } else {
            inventory.setItem(0, GuiManager.glassPane(lang));
        }

        // Footer right away (not after the database round trip): Close must exist even if the lookup is slow.
        GuiManager.applyFooter36(inventory, lang, embedded);

        auth.getPlugin().getDatabaseManager().findPlayer(player.getUniqueId()).thenAccept(record -> {
            if (record.isEmpty()) return;
            DatabaseManager.PlayerRecord pr = record.get();

            Bukkit.getScheduler().runTask(auth.getPlugin(), () -> {
                boolean hasDiscord = pr.hasDiscord();

                // Change / set password
                if (!pr.hasPassword() || !pr.passwordEnabled()) {
                    setItem(SLOT_CHANGE_PASSWORD, HeadTextures.HEAD_CHANGE_PASS, "gui.account.set-password", "gui.account.set-password-lore");
                } else {
                    setItem(SLOT_CHANGE_PASSWORD, HeadTextures.HEAD_CHANGE_PASS, "gui.account.change-password", "gui.account.change-password-lore");
                }

                // Delete password (needs a password to delete and Discord to log in with afterwards)
                if (pr.hasPassword() && pr.passwordEnabled()) {
                    if (hasDiscord) {
                        setItem(SLOT_DELETE_PASSWORD, HeadTextures.HEAD_BARRIER, "gui.account.delete-password", "gui.account.delete-password-lore");
                    } else {
                        setItem(SLOT_DELETE_PASSWORD, HeadTextures.HEAD_INACTIVE, "gui.account.delete-password-locked", "gui.account.delete-password-locked-lore");
                    }
                } else {
                    setItem(SLOT_DELETE_PASSWORD, HeadTextures.HEAD_INACTIVE, "gui.account.delete-password-none", "gui.account.delete-password-none-lore");
                }

                // Session — длительность сессии настраивается прямо здесь
                // (ЛКМ/ПКМ в GuiClickListener), отдельного меню сессий больше нет.
                if (pr.hasPassword() && pr.passwordEnabled()) {
                    int days = pr.sessionDuration();
                    String nameKey = days == 0 ? "gui.session.disable" : "gui.session.days";
                    String headTexture = days == 0 ? HeadTextures.HEAD_INACTIVE : HeadTextures.HEAD_SESSION;
                    auth.getSessionManager().getExpiry(player.getUniqueId()).thenAccept(expiry -> {
                        String status = expiry.isPresent() ? lang.plain("gui.account.session-active") : lang.plain("gui.account.session-disabled");
                        String expires = expiry.map(e -> DF.format(Instant.ofEpochSecond(e))).orElse("---");
                        Bukkit.getScheduler().runTask(auth.getPlugin(), () -> setItem(SLOT_SESSION, headTexture, nameKey, "gui.account.session-lore",
                                Map.of("days", String.valueOf(days), "status", status, "expires", expires)));
                    });
                } else {
                    setItem(SLOT_SESSION, HeadTextures.HEAD_INACTIVE, "gui.account.session-unavailable", "gui.account.session-unavailable-lore");
                }

                // Login method (password or Discord) - only switchable once both are set up
                boolean canSwitchMethod = pr.hasPassword() && pr.passwordEnabled() && hasDiscord;
                boolean preferDiscord = pr.preferredAuthMethod() == AuthMethod.DISCORD;
                String methodStr = lang.plain(preferDiscord ? "gui.account.login-discord" : "gui.account.login-password");
                String methodTexture = preferDiscord ? HeadTextures.HEAD_DISCORD : HeadTextures.HEAD_PASSWORD;
                if (canSwitchMethod) {
                    setItem(SLOT_LOGIN_METHOD, methodTexture, "gui.account.login-method", "gui.account.login-method-lore", Map.of("method", methodStr));
                } else {
                    setItem(SLOT_LOGIN_METHOD, HeadTextures.HEAD_INACTIVE, "gui.account.login-method-locked", "gui.account.login-method-locked-lore", Map.of("method", methodStr));
                }

                // Discord settings
                setItem(SLOT_DISCORD, HeadTextures.HEAD_DISCORD,
                    hasDiscord ? "gui.discord.unlink-button" : "gui.discord.bind-button",
                    hasDiscord ? "gui.discord.unlink-lore" : "gui.discord.bind-lore");

                // Logout (danger action)
                setItem(SLOT_LOGOUT, HeadTextures.HEAD_EXIT_ACCOUNT, "gui.account.logout", "gui.account.logout-lore");

            });
        });
    }

    /** Выполняет команду возврата (gui.account.back-command из config.yml) в embedded-режиме. */
    public void goBack() {
        if (!embedded) return;
        String cmd = config.getAccountBackCommand();
        if (!cmd.isBlank()) player.performCommand(cmd);
    }

    private void setItem(int slot, String headTexture, String nameKey, String loreKey) { setItem(slot, headTexture, nameKey, loreKey, Map.of()); }
    private void setItem(int slot, String headTexture, String nameKey, String loreKey, Map<String, String> p) {
        inventory.setItem(slot, HeadTextures.createSkull(headTexture, lang.component(nameKey, p), lang.lore(loreKey, p)));
    }

    @Override
    public @NotNull Inventory getInventory() { return inventory; }
}
