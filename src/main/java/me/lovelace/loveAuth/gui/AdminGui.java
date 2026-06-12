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
        refresh();
        player.openInventory(inventory);
    }

    public void refresh() {
        GuiManager.fillBackground(inventory, lang);

        plugin.getDatabaseManager().getLockedUsernames().thenAccept(lockedList -> {
            Bukkit.getScheduler().runTask(plugin, () -> setItem(20, Material.BARRIER, "gui.admin.locked-accounts", "gui.admin.locked-accounts-lore", Map.of("count", Integer.toString(lockedList.size()))));
        });

        setItem(22, Material.HOPPER, "gui.admin.unlock-player", "gui.admin.unlock-player-lore");

        plugin.getDatabaseManager().getStats().thenAccept(stats -> {
            Bukkit.getScheduler().runTask(plugin, () -> {
                setItem(24, Material.JUKEBOX, "gui.admin.stats", "gui.admin.stats-lore", Map.of(
                        "online", Integer.toString(Bukkit.getOnlinePlayers().size()),
                        "registered", Integer.toString(stats.registered()),
                        "locked", Integer.toString(stats.locked())
                ));

                setItem(31, Material.CLOCK, "gui.admin.sessions", "gui.admin.sessions-lore", Map.of("sessions", Integer.toString(stats.sessions())));
                setItem(32, Material.BLAZE_POWDER, "gui.admin.clear-ip-blocks", "gui.admin.clear-ip-blocks-lore");
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
