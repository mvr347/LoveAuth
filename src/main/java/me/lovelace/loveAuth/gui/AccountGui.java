package me.lovelace.loveAuth.gui;

import me.lovelace.loveAuth.auth.AuthManager;
import me.lovelace.loveAuth.config.ConfigManager;
import me.lovelace.loveAuth.database.DatabaseManager;
import me.lovelace.loveAuth.lang.LangManager;
import me.lovelace.loveAuth.util.HeadTextures;
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
    private static final int SIZE = 27;
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
        GuiManager.standardFrame27(inventory, lang);
        // Слот 0 (профиль) и кнопка "Назад" видны только в embedded-режиме (открыто со
        // скрытым аргументом — меню встроено кнопкой в чьё-то DeluxeMenus-меню), и только
        // если это не отключено в config.yml. Обычный "/loveauth account" остаётся
        // самостоятельным экраном без профиля и без кнопки "Назад".
        if (embedded && config.isAccountProfileEnabled()) {
            inventory.setItem(0, GuiManager.playerHead(player, lang));
        }

        auth.getPlugin().getDatabaseManager().findPlayer(player.getUniqueId()).thenAccept(record -> {
            if (record.isEmpty()) return;
            DatabaseManager.PlayerRecord pr = record.get();

            Bukkit.getScheduler().runTask(auth.getPlugin(), () -> {
                boolean hasDiscord = pr.hasDiscord();

                // Slot 11: Change Password
                if (!pr.hasPassword() || !pr.passwordEnabled()) {
                    setItem(11, HeadTextures.HEAD_CHANGE_PASS, "gui.account.set-password", "gui.account.set-password-lore");
                } else {
                    setItem(11, HeadTextures.HEAD_CHANGE_PASS, "gui.account.change-password", "gui.account.change-password-lore");
                }

                // Slot 12: Delete Password (Discord only)
                if (pr.hasPassword() && pr.passwordEnabled()) {
                    if (hasDiscord) {
                        setItem(12, HeadTextures.HEAD_BARRIER, "gui.account.delete-password", "gui.account.delete-password-lore");
                    } else {
                        setItem(12, HeadTextures.HEAD_INACTIVE, "gui.account.delete-password-locked", "gui.account.delete-password-locked-lore");
                    }
                }

                // Slot 13: Session — длительность сессии настраивается прямо здесь
                // (ЛКМ/ПКМ в GuiClickListener), отдельного меню сессий больше нет.
                if (pr.hasPassword() && pr.passwordEnabled()) {
                    int days = pr.sessionDuration();
                    String nameKey = days == 0 ? "gui.session.disable" : "gui.session.days";
                    String headTexture = days == 0 ? HeadTextures.HEAD_INACTIVE : HeadTextures.HEAD_SESSION;
                    auth.getSessionManager().getExpiry(player.getUniqueId()).thenAccept(expiry -> {
                        String status = expiry.isPresent() ? lang.plain("gui.account.session-active") : lang.plain("gui.account.session-disabled");
                        String expires = expiry.map(e -> DF.format(Instant.ofEpochSecond(e))).orElse("---");
                        Bukkit.getScheduler().runTask(auth.getPlugin(), () -> setItem(13, headTexture, nameKey, "gui.account.session-lore",
                                Map.of("days", String.valueOf(days), "status", status, "expires", expires)));
                    });
                } else {
                    setItem(13, HeadTextures.HEAD_INACTIVE, "gui.account.session-unavailable", "gui.account.session-unavailable-lore");
                }

                // Slot 14: Input Method
                auth.resolveInputMethod(player).thenAccept(method -> {
                    String mStr = lang.plain(method == me.lovelace.loveAuth.input.InputMethod.CHAT ? "gui.account.input-chat" : "gui.account.input-sign");
                    Bukkit.getScheduler().runTask(auth.getPlugin(), () -> setItem(14, HeadTextures.HEAD_INFO, "gui.account.input-method", "gui.account.input-method-lore", Map.of("method", mStr)));
                });

                // Slot 15: Discord Settings
                setItem(15, HeadTextures.HEAD_DISCORD,
                    hasDiscord ? "gui.discord.unlink-button" : "gui.discord.bind-button",
                    hasDiscord ? "gui.discord.unlink-lore" : "gui.discord.bind-lore");

                // Slot 16: Logout (danger action)
                setItem(16, HeadTextures.HEAD_EXIT_ACCOUNT, "gui.account.logout", "gui.account.logout-lore");

                if (embedded) {
                    inventory.setItem(BACK_SLOT, HeadTextures.createSkull(HeadTextures.HEAD_BACK, lang.component("gui.back-button"), List.of()));
                }
                inventory.setItem(CLOSE_SLOT, HeadTextures.createSkull(HeadTextures.HEAD_BARRIER, lang.component("gui.close-button"), List.of()));
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
