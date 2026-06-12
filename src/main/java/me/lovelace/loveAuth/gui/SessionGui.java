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

import java.util.Map;

public final class SessionGui implements LoveAuthHolder {
    private final Player player;
    private final LangManager lang;
    private final AuthManager auth;
    private Inventory inventory;

    public SessionGui(Player player, LangManager lang, AuthManager auth) {
        this.player = player;
        this.lang = lang;
        this.auth = auth;
    }

    public void open() {
        this.inventory = Bukkit.createInventory(this, 27, lang.component("gui.account.session-info"));
        refresh();
        player.openInventory(inventory);
    }

    public void refresh() {
        GuiManager.fillBackground(inventory, lang);

        auth.getPlugin().getDatabaseManager().findPlayer(player.getUniqueId()).thenAccept(record -> {
            int currentDays = record.map(DatabaseManager.PlayerRecord::sessionDuration).orElse(7);
            
            Bukkit.getScheduler().runTask(auth.getPlugin(), () -> {
                ItemStack cycleBtn = new ItemStack(currentDays == 0 ? Material.BARRIER : Material.CLOCK);
                ItemMeta cycleMeta = cycleBtn.getItemMeta();
                if (cycleMeta != null) {
                    String nameKey = currentDays == 0 ? "gui.session.disable" : "gui.session.days";
                    cycleMeta.displayName(lang.component(nameKey, Map.of("days", String.valueOf(currentDays))));
                    cycleMeta.lore(lang.lore("gui.session.cycle-lore"));
                    cycleBtn.setItemMeta(cycleMeta);
                }
                inventory.setItem(13, cycleBtn);

                ItemStack back = new ItemStack(Material.ARROW);
                ItemMeta backMeta = back.getItemMeta();
                if (backMeta != null) {
                    backMeta.displayName(lang.component("gui.back-button"));
                    back.setItemMeta(backMeta);
                }
                inventory.setItem(22, back);
            });
        });
    }

    @Override
    public @NotNull Inventory getInventory() { return inventory; }
}
