package me.lovelace.loveAuth.util;

import org.bukkit.Sound;
import org.bukkit.entity.Player;

/** Feedback sounds for GUI interactions and auth-flow outcomes. */
public final class SoundUtils {

    private SoundUtils() {
    }

    public static void click(Player player) {
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.2f);
    }

    public static void success(Player player) {
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 1.5f);
    }

    public static void error(Player player) {
        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1.0f);
    }

    public static void open(Player player) {
        player.playSound(player.getLocation(), Sound.BLOCK_CHEST_OPEN, 0.4f, 1.0f);
    }
}
