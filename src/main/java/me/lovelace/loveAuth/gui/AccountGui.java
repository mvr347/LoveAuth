package me.lovelace.loveAuth.gui;

import me.lovelace.loveAuth.auth.AuthManager;
import me.lovelace.loveAuth.database.DatabaseManager;
import me.lovelace.loveAuth.lang.LangManager;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;

public final class AccountGui implements LoveAuthHolder {
    private final Player player;
    private final LangManager lang;
    private final AuthManager auth;
    private Inventory inventory;
    private static final DateTimeFormatter DF = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault());

    public AccountGui(Player player, LangManager lang, AuthManager auth) {
        this.player = player;
        this.lang = lang;
        this.auth = auth;
    }

    public void open() {
        this.inventory = Bukkit.createInventory(this, 54, lang.component("gui.account.title"));
        refresh();
        player.openInventory(inventory);
    }

    public void refresh() {
        GuiManager.fillBackground(inventory, lang);

        auth.getPlugin().getDatabaseManager().findPlayer(player.getUniqueId()).thenAccept(record -> {
            if (record.isEmpty()) return;
            DatabaseManager.PlayerRecord pr = record.get();

            Bukkit.getScheduler().runTask(auth.getPlugin(), () -> {
                boolean hasDiscord = pr.hasDiscord();

                // PASSWORD (Slots 19, 20, 21)
                if (!pr.hasPassword() || !pr.passwordEnabled()) {
                    setItem(20, Material.TRIPWIRE_HOOK, "gui.account.set-password", "gui.account.set-password-lore");
                } else {
                    setItem(19, Material.TRIPWIRE_HOOK, "gui.account.change-password", "gui.account.change-password-lore");
                    if (hasDiscord) {
                        setItem(21, Material.BARRIER, "gui.account.delete-password", "gui.account.delete-password-lore");
                    } else {
                        setItem(21, Material.GRAY_DYE, "gui.account.delete-password-locked", "gui.account.delete-password-locked-lore");
                    }
                }

                // DISCORD (Slot 24)
                setItem(24, hasDiscord ? Material.CHAINMAIL_CHESTPLATE : Material.CHAIN, 
                    hasDiscord ? "gui.discord.unlink-button" : "gui.discord.bind-button",
                    hasDiscord ? "gui.discord.unlink-lore" : "gui.discord.bind-lore");

                // LOCK ACCOUNT (Slot 25)
                if (hasDiscord) {
                    setItem(25, Material.IRON_BARS, "gui.account.lock-account", "gui.account.lock-account-lore");
                }

                // SESSION (Slot 22)
                if (pr.hasPassword() && pr.passwordEnabled()) {
                    auth.getSessionManager().getExpiry(player.getUniqueId()).thenAccept(expiry -> {
                        String status = expiry.isPresent() ? lang.plain("gui.account.session-active") : lang.plain("gui.account.session-disabled");
                        String expires = expiry.map(e -> DF.format(Instant.ofEpochSecond(e))).orElse("---");
                        Bukkit.getScheduler().runTask(auth.getPlugin(), () -> setItem(22, Material.CLOCK, "gui.account.session-info", "gui.account.session-lore", Map.of("status", status, "expires", expires)));
                    });
                } else {
                    setItem(22, Material.GRAY_DYE, "gui.account.session-unavailable", "gui.account.session-unavailable-lore");
                }

                // INPUT METHOD (Slot 23)
                auth.resolveInputMethod(player).thenAccept(method -> {
                    String mStr = lang.plain(method == me.lovelace.loveAuth.input.InputMethod.CHAT ? "gui.account.input-chat" : "gui.account.input-sign");
                    Bukkit.getScheduler().runTask(auth.getPlugin(), () -> setItem(23, Material.OAK_SIGN, "gui.account.input-method", "gui.account.input-method-lore", Map.of("method", mStr)));
                });

                // LOGOUT (Slot 31)
                setItem(31, Material.IRON_DOOR, "gui.account.logout", "gui.account.logout-lore");
            });
        });
    }

    private void setItem(int slot, Material m, String nameKey, String loreKey) { setItem(slot, m, nameKey, loreKey, Map.of()); }
    private void setItem(int slot, Material m, String nameKey, String loreKey, Map<String, String> p) {
        ItemStack item = new ItemStack(m);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) { meta.displayName(lang.component(nameKey, p)); meta.lore(lang.lore(loreKey, p)); item.setItemMeta(meta); }
        inventory.setItem(slot, item);
    }

    @Override
    public @NotNull Inventory getInventory() { return inventory; }
}
