package me.lovelace.loveAuth.gui;

import me.lovelace.loveAuth.LoveAuth;
import me.lovelace.loveAuth.auth.AuthManager;
import me.lovelace.loveAuth.lang.LangManager;
import me.lovelace.loveAuth.util.HeadTextures;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
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
        inventory.setItem(0, GuiManager.playerHead(player, lang));

        plugin.getDatabaseManager().getLockedUsernames().thenAccept(lockedList -> {
            Bukkit.getScheduler().runTask(plugin, () -> setItem(2, HeadTextures.HEAD_INACTIVE, "gui.admin.locked-accounts", "gui.admin.locked-accounts-lore", Map.of("count", Integer.toString(lockedList.size()))));
        });

        setItem(3, HeadTextures.HEAD_CHECK, "gui.admin.unlock-player", "gui.admin.unlock-player-lore");

        plugin.getDatabaseManager().getStats().thenAccept(stats -> {
            Bukkit.getScheduler().runTask(plugin, () -> {
                setItem(4, HeadTextures.HEAD_INFO, "gui.admin.stats", "gui.admin.stats-lore", Map.of(
                        "online", Integer.toString(Bukkit.getOnlinePlayers().size()),
                        "registered", Integer.toString(stats.registered()),
                        "locked", Integer.toString(stats.locked())
                ));

                setItem(5, HeadTextures.HEAD_SESSION, "gui.admin.sessions", "gui.admin.sessions-lore", Map.of("sessions", Integer.toString(stats.sessions())));
                setItem(6, HeadTextures.HEAD_X, "gui.admin.clear-ip-blocks", "gui.admin.clear-ip-blocks-lore");
            });
        });

        // Слот 53: Close (footer, стандарт gui_gen)
        inventory.setItem(53, HeadTextures.createSkull(HeadTextures.HEAD_BARRIER, lang.component("gui.close-button"), java.util.Collections.emptyList()));
    }

    private void setItem(int slot, String headTexture, String nameKey, String loreKey) { setItem(slot, headTexture, nameKey, loreKey, Map.of()); }
    private void setItem(int slot, String headTexture, String nameKey, String loreKey, Map<String, String> p) {
        inventory.setItem(slot, HeadTextures.createSkull(headTexture, lang.component(nameKey, p), lang.lore(loreKey, p)));
    }

    @Override
    public @NotNull Inventory getInventory() { return inventory; }
}
