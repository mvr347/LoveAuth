package me.lovelace.loveAuth.gui;

import me.lovelace.loveAuth.auth.AuthManager;
import me.lovelace.loveAuth.database.DatabaseManager;
import me.lovelace.loveAuth.lang.LangManager;
import me.lovelace.loveAuth.util.HeadTextures;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
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
        inventory.setItem(0, GuiManager.playerHead(player, lang));

        auth.getPlugin().getDatabaseManager().findPlayer(player.getUniqueId()).thenAccept(record -> {
            int currentDays = record.map(DatabaseManager.PlayerRecord::sessionDuration).orElse(7);

            Bukkit.getScheduler().runTask(auth.getPlugin(), () -> {
                String nameKey = currentDays == 0 ? "gui.session.disable" : "gui.session.days";
                ItemStack cycleBtn;
                if (currentDays == 0) {
                    cycleBtn = HeadTextures.createSkull(HeadTextures.HEAD_INACTIVE,
                            lang.component(nameKey, Map.of("days", String.valueOf(currentDays))),
                            lang.lore("gui.session.cycle-lore"));
                } else {
                    cycleBtn = HeadTextures.createSkull(HeadTextures.HEAD_SESSION,
                            lang.component(nameKey, Map.of("days", String.valueOf(currentDays))),
                            lang.lore("gui.session.cycle-lore"));
                }
                inventory.setItem(4, cycleBtn);

                GuiManager.applyFooter27(inventory, lang, true);
            });
        });
    }

    @Override
    public @NotNull Inventory getInventory() { return inventory; }
}
