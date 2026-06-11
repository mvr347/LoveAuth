package me.lovelace.loveAuth.gui;

import me.lovelace.loveAuth.LoveAuth;
import me.lovelace.loveAuth.auth.AuthManager;
import me.lovelace.loveAuth.lang.LangManager;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

public final class AdminGui implements LoveAuthHolder {
    private final Player player;
    private final LoveAuth plugin;
    private final LangManager lang;
    private final AuthManager auth;
    private Inventory inventory;

    public AdminGui(Player player, LoveAuth plugin, LangManager lang, AuthManager auth) {
        this.player = player;
        this.plugin = plugin;
        this.lang = lang;
        this.auth = auth;
    }

    public void open() {
        this.inventory = Bukkit.createInventory(this, 54, lang.component("gui.admin.title"));
        GuiManager.fillBackground(inventory, lang);

        plugin.getDatabaseManager().getLockedUsernames().thenAccept(lockedList -> {
            ItemStack locked = new ItemStack(Material.BARRIER);
            ItemMeta lockedMeta = locked.getItemMeta();
            if (lockedMeta != null) {
                lockedMeta.displayName(lang.component("gui.admin.locked-accounts"));
                lockedMeta.lore(lang.lore("gui.admin.locked-accounts-lore", Map.of("count", Integer.toString(lockedList.size()))));
                locked.setItemMeta(lockedMeta);
            }
            inventory.setItem(20, locked);
        });

        ItemStack unlock = new ItemStack(Material.HOPPER);
        ItemMeta unlockMeta = unlock.getItemMeta();
        if (unlockMeta != null) {
            unlockMeta.displayName(lang.component("gui.admin.unlock-player"));
            unlockMeta.lore(lang.lore("gui.admin.unlock-player-lore"));
            unlock.setItemMeta(unlockMeta);
        }
        inventory.setItem(22, unlock);

        plugin.getDatabaseManager().getStats().thenAccept(stats -> {
            ItemStack statsItem = new ItemStack(Material.JUKEBOX);
            ItemMeta statsMeta = statsItem.getItemMeta();
            if (statsMeta != null) {
                statsMeta.displayName(lang.component("gui.admin.stats"));
                statsMeta.lore(lang.lore("gui.admin.stats-lore", Map.of(
                        "online", Integer.toString(Bukkit.getOnlinePlayers().size()),
                        "registered", Integer.toString(stats.registered()),
                        "locked", Integer.toString(stats.locked())
                )));
                statsItem.setItemMeta(statsMeta);
            }
            inventory.setItem(24, statsItem);

            ItemStack sessions = new ItemStack(Material.CLOCK);
            ItemMeta sessionsMeta = sessions.getItemMeta();
            if (sessionsMeta != null) {
                sessionsMeta.displayName(lang.component("gui.admin.sessions"));
                sessionsMeta.lore(lang.lore("gui.admin.sessions-lore", Map.of("sessions", Integer.toString(stats.sessions()))));
                sessions.setItemMeta(sessionsMeta);
            }
            inventory.setItem(31, sessions);
        });

        player.openInventory(inventory);
    }

    @Override
    public @NotNull Inventory getInventory() { return inventory; }
}
