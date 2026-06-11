package me.lovelace.loveAuth.gui;

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
        GuiManager.fillBackground(inventory, lang);

        int[] days = {0, 1, 3, 7, 14, 28};
        int[] slots = {10, 11, 12, 13, 14, 15};

        for (int i = 0; i < days.length; i++) {
            int d = days[i];
            ItemStack item = new ItemStack(d == 0 ? Material.BARRIER : Material.CLOCK);
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                String key = d == 0 ? "gui.session.disable" : "gui.session.days";
                meta.displayName(lang.component(key, Map.of("days", String.valueOf(d))));
                item.setItemMeta(meta);
            }
            inventory.setItem(slots[i], item);
        }

        ItemStack back = new ItemStack(Material.ARROW);
        ItemMeta backMeta = back.getItemMeta();
        if (backMeta != null) {
            backMeta.displayName(lang.component("gui.back-button"));
            back.setItemMeta(backMeta);
        }
        inventory.setItem(22, back);

        player.openInventory(inventory);
    }

    @Override
    public @NotNull Inventory getInventory() { return inventory; }
}
