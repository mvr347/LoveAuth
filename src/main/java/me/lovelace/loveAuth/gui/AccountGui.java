package me.lovelace.loveAuth.gui;

import me.lovelace.loveAuth.auth.AuthManager;
import me.lovelace.loveAuth.database.DatabaseManager;
import me.lovelace.loveAuth.lang.LangManager;
import me.lovelace.loveAuth.util.HeadTextures;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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
    private final String returnCommand;
    private Inventory inventory;
    private static final DateTimeFormatter DF = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault());

    public AccountGui(Player player, LangManager lang, AuthManager auth) {
        this(player, lang, auth, null);
    }

    /**
     * @param returnCommand если меню открыто кнопкой из DeluxeMenus — команда для возврата
     *                      в то меню (выполняется по клику на "Назад"). Null для обычного
     *                      открытия командой плагина — тогда слота профиля/кнопки "Назад" нет.
     */
    public AccountGui(Player player, LangManager lang, AuthManager auth, @Nullable String returnCommand) {
        this.player = player;
        this.lang = lang;
        this.auth = auth;
        this.returnCommand = returnCommand;
    }

    public void open() {
        this.inventory = Bukkit.createInventory(this, SIZE, lang.component("gui.account.title"));
        refresh();
        player.openInventory(inventory);
    }

    public void refresh() {
        GuiManager.fillBackground(inventory, lang);
        // Слот 0 (профиль) виден только если меню открыто с returnCommand (скрытый
        // аргумент "/loveauth account <cmd>", напр. "mainmenu") — оно встроено кнопкой
        // в чей-то DeluxeMenus-меню и должно уметь вернуться назад. Обычный "/loveauth
        // account" остаётся самостоятельным экраном без профиля и без кнопки "Назад".
        if (returnCommand != null) {
            inventory.setItem(0, GuiManager.playerHead(player, lang));
        }

        auth.getPlugin().getDatabaseManager().findPlayer(player.getUniqueId()).thenAccept(record -> {
            if (record.isEmpty()) return;
            DatabaseManager.PlayerRecord pr = record.get();

            Bukkit.getScheduler().runTask(auth.getPlugin(), () -> {
                boolean hasDiscord = pr.hasDiscord();

                // Slot 2: Change Password
                if (!pr.hasPassword() || !pr.passwordEnabled()) {
                    setItem(2, HeadTextures.HEAD_CHANGE_PASS, "gui.account.set-password", "gui.account.set-password-lore");
                } else {
                    setItem(2, HeadTextures.HEAD_CHANGE_PASS, "gui.account.change-password", "gui.account.change-password-lore");
                }

                // Slot 3: Delete Password (Discord only)
                if (pr.hasPassword() && pr.passwordEnabled()) {
                    if (hasDiscord) {
                        setItem(3, HeadTextures.HEAD_BARRIER, "gui.account.delete-password", "gui.account.delete-password-lore");
                    } else {
                        setItem(3, HeadTextures.HEAD_INACTIVE, "gui.account.delete-password-locked", "gui.account.delete-password-locked-lore");
                    }
                }

                // Slot 4: Session Info
                if (pr.hasPassword() && pr.passwordEnabled()) {
                    auth.getSessionManager().getExpiry(player.getUniqueId()).thenAccept(expiry -> {
                        String status = expiry.isPresent() ? lang.plain("gui.account.session-active") : lang.plain("gui.account.session-disabled");
                        String expires = expiry.map(e -> DF.format(Instant.ofEpochSecond(e))).orElse("---");
                        Bukkit.getScheduler().runTask(auth.getPlugin(), () -> setItem(4, HeadTextures.HEAD_SESSION, "gui.account.session-info", "gui.account.session-lore", Map.of("status", status, "expires", expires)));
                    });
                } else {
                    setItem(4, HeadTextures.HEAD_INACTIVE, "gui.account.session-unavailable", "gui.account.session-unavailable-lore");
                }

                // Slot 5: Input Method
                auth.resolveInputMethod(player).thenAccept(method -> {
                    String mStr = lang.plain(method == me.lovelace.loveAuth.input.InputMethod.CHAT ? "gui.account.input-chat" : "gui.account.input-sign");
                    Bukkit.getScheduler().runTask(auth.getPlugin(), () -> setItem(5, HeadTextures.HEAD_INFO, "gui.account.input-method", "gui.account.input-method-lore", Map.of("method", mStr)));
                });

                // Slot 6: Discord Settings
                setItem(6, HeadTextures.HEAD_DISCORD,
                    hasDiscord ? "gui.discord.unlink-button" : "gui.discord.bind-button",
                    hasDiscord ? "gui.discord.unlink-lore" : "gui.discord.bind-lore");

                // Slot 7: Logout (danger action, separated from the rest of the row)
                setItem(7, HeadTextures.HEAD_EXIT_ACCOUNT, "gui.account.logout", "gui.account.logout-lore");

                if (returnCommand != null) {
                    inventory.setItem(BACK_SLOT, HeadTextures.createSkull(HeadTextures.HEAD_BACK, lang.component("gui.back-button"), List.of()));
                }
                inventory.setItem(CLOSE_SLOT, HeadTextures.createSkull(HeadTextures.HEAD_BARRIER, lang.component("gui.close-button"), List.of()));
            });
        });
    }

    /** Выполняет команду возврата в DeluxeMenus-меню, если она была задана при открытии. */
    public void goBack() {
        if (returnCommand != null) {
            player.performCommand(returnCommand);
        }
    }

    private void setItem(int slot, String headTexture, String nameKey, String loreKey) { setItem(slot, headTexture, nameKey, loreKey, Map.of()); }
    private void setItem(int slot, String headTexture, String nameKey, String loreKey, Map<String, String> p) {
        inventory.setItem(slot, HeadTextures.createSkull(headTexture, lang.component(nameKey, p), lang.lore(loreKey, p)));
    }

    @Override
    public @NotNull Inventory getInventory() { return inventory; }
}
