package me.lovelace.loveAuth.gui;

import me.lovelace.loveAuth.auth.AuthManager;
import me.lovelace.loveAuth.lang.LangManager;
import me.lovelace.loveAuth.textures.HeadTextures;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import org.jetbrains.annotations.NotNull;

public final class RegisterGui implements LoveAuthHolder {
    private final Player player;
    private final LangManager lang;
    private final AuthManager auth;
    private Inventory inventory;
    /** True when the big ItemsAdder window is open (buttons are zones, not single slots). */
    private boolean zoned;

    public RegisterGui(Player player, LangManager lang, AuthManager auth) {
        this.player = player;
        this.lang = lang;
        this.auth = auth;
    }

    public void open() {
        boolean discordEnabled = auth.getPlugin().getConfigManager().isDiscordEnabled();
        LoreBlocks blocks = new LoreBlocks(lang);
        if (discordEnabled && AuthMenuLayout.available(player, AuthMenuLayout.Menu.REGISTER)) {
            ItemStack password = AuthMenuLayout.button(GuiManager.button(lang, HeadTextures.HEAD_PASSWORD, "gui.register.register-button",
                    "gui.register.register-lore", blocks.clicks(blocks.left("gui.register.register-action"))));
            ItemStack discord = AuthMenuLayout.button(GuiManager.button(lang, HeadTextures.HEAD_DISCORD, "gui.discord.bind-button",
                    "gui.discord.bind-lore", blocks.clicks(blocks.left("gui.discord.bind-action"))));
            if (password != null && discord != null) {
                this.zoned = true;
                this.inventory = Bukkit.createInventory(this, AuthMenuLayout.SIZE, AuthMenuLayout.title(player, AuthMenuLayout.Menu.REGISTER));
                AuthMenuLayout.fill(inventory, AuthMenuLayout.Menu.REGISTER,
                        Map.of(AuthMenuLayout.Zone.PASSWORD, password, AuthMenuLayout.Zone.DISCORD, discord));
                player.openInventory(inventory);
                return;
            }
        }
        this.zoned = false;
        this.inventory = Bukkit.createInventory(this, 27, lang.component("gui.register.title"));
        GuiManager.standardFrame27(inventory, lang);
        inventory.setItem(0, GuiManager.playerHead(player, lang));

        int[] slots = slots(discordEnabled);

        inventory.setItem(slots[0], GuiManager.button(lang, HeadTextures.HEAD_PASSWORD, "gui.register.register-button",
                "gui.register.register-lore", blocks.clicks(blocks.left("gui.register.register-action"))));

        if (discordEnabled) {
            inventory.setItem(slots[1], GuiManager.button(lang, HeadTextures.HEAD_DISCORD, "gui.discord.bind-button",
                    "gui.discord.bind-lore", blocks.clicks(blocks.left("gui.discord.bind-action"))));
        }

        GuiManager.applyFooter27(inventory, lang, false);

        player.openInventory(inventory);
    }

    public boolean zoned() {
        return zoned;
    }

    /** Which button a slot of the big window belongs to. */
    public java.util.Optional<AuthMenuLayout.Zone> zoneAt(int slot) {
        return zoned ? AuthMenuLayout.zoneOf(AuthMenuLayout.Menu.REGISTER, slot) : java.util.Optional.empty();
    }

    /** Button slots: the register button first, then Discord when it is enabled - always centred. */
    public static int[] slots(boolean discordEnabled) {
        return GuiManager.centeredRow(9, discordEnabled ? 2 : 1);
    }

    @Override
    public @NotNull Inventory getInventory() { return inventory; }
}
