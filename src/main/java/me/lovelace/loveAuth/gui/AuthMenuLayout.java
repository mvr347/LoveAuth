package me.lovelace.loveAuth.gui;

import me.clip.placeholderapi.PlaceholderAPI;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.regex.Pattern;

/**
 * The two big ItemsAdder registration menus (owner request 2026-11-08): 27 slots, a resource-pack picture in the
 * title and invisible button zones. {@code register_menu} has two zones (password, Discord) laid out like the
 * confirm menu; {@code premium_register_menu} has three 3x3 zones (password, Discord, skip). Every slot of a zone
 * carries the same item, so hovering anywhere in the zone shows the button's name and lore. Without ItemsAdder /
 * PlaceholderAPI, the invisible item or the picture, callers keep their small heads-based windows.
 */
public final class AuthMenuLayout {

    public static final int SIZE = 27;
    /** ItemsAdder namespaced id; "itemsadder-" is only the DeluxeMenus prefix and is dropped by {@link #itemId}. */
    public static final String INVISIBLE_ITEM = "voidcore:invisible";

    public enum Menu {
        REGISTER("<white>%img_offset_-8%%img_register_menu%",
                new int[]{1, 2, 3, 10, 11, 12, 19, 20, 21}, new int[]{5, 6, 7, 14, 15, 16, 23, 24, 25}, null),
        PREMIUM_REGISTER("<white>%img_offset_-8%%img_premium_register_menu%",
                new int[]{0, 1, 2, 9, 10, 11, 18, 19, 20}, new int[]{3, 4, 5, 12, 13, 14, 21, 22, 23},
                new int[]{6, 7, 8, 15, 16, 17, 24, 25, 26});

        private final String title;
        private final int[] password;
        private final int[] discord;
        private final int[] skip;

        Menu(String title, int[] password, int[] discord, int[] skip) {
            this.title = title;
            this.password = password;
            this.discord = discord;
            this.skip = skip;
        }

        public String title() {
            return title;
        }

        int[] slots(Zone zone) {
            return switch (zone) {
                case PASSWORD -> password;
                case DISCORD -> discord;
                case SKIP -> skip == null ? new int[0] : skip;
            };
        }
    }

    public enum Zone { PASSWORD, DISCORD, SKIP }

    private static final Pattern COLON_TOKEN = Pattern.compile(":[A-Za-z0-9_\\-]{2,}:");

    private AuthMenuLayout() {
    }

    /** Which button the slot belongs to, empty for a slot outside every zone. */
    public static Optional<Zone> zoneOf(Menu menu, int slot) {
        for (Zone zone : Zone.values()) {
            for (int s : menu.slots(zone)) if (s == slot) return Optional.of(zone);
        }
        return Optional.empty();
    }

    /** The id as ItemsAdder knows it: the DeluxeMenus prefix "itemsadder-" is dropped. */
    public static String itemId(String id) {
        return id != null && id.startsWith("itemsadder-") ? id.substring("itemsadder-".length()) : id;
    }

    /** A title counts as resolved when no unresolved placeholder is left in it. */
    static boolean backgroundResolved(String resolved) {
        return resolved != null && !resolved.isBlank()
                && !resolved.contains("%img_") && !resolved.contains("%offset")
                && !COLON_TOKEN.matcher(resolved).find();
    }

    private static final java.util.Set<Menu> reported = java.util.EnumSet.noneOf(Menu.class);

    /**
     * Why the big window cannot be used for this viewer, or null when it can. A disabled Discord is a reason too:
     * both big menus have a Discord button.
     */
    public static String reasonFor(Player viewer, Menu menu, boolean discordEnabled) {
        if (!discordEnabled) return "discord.enabled is false in LoveAuth config.yml (both big menus have a Discord button)";
        return unavailableReason(viewer, menu);
    }

    /** Logs once per menu and run which window is shown, so the console says what a player is looking at. */
    public static void report(Menu menu, String why) {
        synchronized (reported) {
            if (!reported.add(menu)) return;
        }
        String name = menu == Menu.REGISTER ? "register_menu" : "premium_register_menu";
        if (why == null) Bukkit.getLogger().info("[LoveAuth] Registration menu " + name + ": the big ItemsAdder window is used");
        else Bukkit.getLogger().warning("[LoveAuth] Registration menu " + name + ": the small window is shown instead: " + why);
    }

    static String unavailableReason(Player viewer, Menu menu) {
        if (!Bukkit.getPluginManager().isPluginEnabled("ItemsAdder")) return "ItemsAdder is not enabled";
        if (!Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) return "PlaceholderAPI is not enabled";
        if (invisible() == null) return "ItemsAdder has no item '" + itemId(INVISIBLE_ITEM) + "'";
        if (!backgroundResolved(resolve(viewer, menu.title)))
            return "the picture in '" + menu.title + "' does not resolve (font image missing?)";
        return null;
    }

    public static Component title(Player viewer, Menu menu) {
        return MiniMessage.miniMessage().deserialize(resolve(viewer, menu.title));
    }

    /** The invisible item, or null when ItemsAdder does not know it. Reflection through ItemsAdder's own class loader. */
    private static ItemStack invisible() {
        Plugin itemsAdder = Bukkit.getPluginManager().getPlugin("ItemsAdder");
        if (itemsAdder == null || !itemsAdder.isEnabled()) return null;
        try {
            Class<?> stack = Class.forName("dev.lone.itemsadder.api.CustomStack", true, itemsAdder.getClass().getClassLoader());
            Object custom = stack.getMethod("getInstance", String.class).invoke(null, itemId(INVISIBLE_ITEM));
            if (custom == null) return null;
            Object item = stack.getMethod("getItemStack").invoke(custom);
            return item instanceof ItemStack itemStack ? itemStack.clone() : null;
        } catch (Throwable error) {
            Bukkit.getLogger().log(Level.FINEST, "ItemsAdder invisible item failed: " + error.getMessage());
            return null;
        }
    }

    /** The invisible item carrying the name and lore of {@code source}, or null when ItemsAdder has no such item. */
    public static ItemStack button(ItemStack source) {
        ItemStack item = invisible();
        if (item == null) return null;
        var meta = source.getItemMeta();
        item.editMeta(target -> {
            target.displayName(meta.displayName());
            target.lore(meta.lore());
        });
        return item;
    }

    /** Puts {@code items} into the zones of {@code menu}; a zone without an item stays empty. */
    public static void fill(Inventory inventory, Menu menu, Map<Zone, ItemStack> items) {
        for (Map.Entry<Zone, ItemStack> entry : items.entrySet())
            for (int slot : menu.slots(entry.getKey())) inventory.setItem(slot, entry.getValue());
    }

    /** Resolves %img_x% through PlaceholderAPI (ItemsAdder registers them), glyph wrapped in white. */
    private static String resolve(Player viewer, String text) {
        String wrapped = text.replaceAll("%img_([a-zA-Z0-9_:]+)%", "<white>%img_$1%</white>");
        if (!Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) return wrapped;
        try {
            return PlaceholderAPI.setPlaceholders(viewer, wrapped);
        } catch (Throwable failure) {
            Bukkit.getLogger().log(Level.FINEST, "PlaceholderAPI resolution failed: " + failure.getMessage());
            return wrapped;
        }
    }
}
