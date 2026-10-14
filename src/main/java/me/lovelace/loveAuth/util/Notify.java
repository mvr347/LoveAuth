package me.lovelace.loveAuth.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.function.BooleanSupplier;

/**
 * Routes event-style notices through LoveCore's LoveNotify so each player's /lovenotify choice
 * applies (LoveCore itself falls back to chat for long text, a disabled channel or an important
 * message). The service is looked up on every call, never cached, and without LoveCore - or when its
 * classes cannot be linked - the notice goes straight to the channel it was meant for.
 *
 * Not for command replies, input errors, login/register instructions or the ticking queue/prompt
 * action bars: those must reach the player regardless of their notification settings and are sent
 * directly by their callers.
 */
public final class Notify {
    private Notify() {}

    /** A short status or confirmation: action bar by default. */
    public static void event(Player player, Component message) {
        deliver(() -> CoreBridge.send(player, message, false, false), () -> player.sendActionBar(message));
    }

    /** Same, but always duplicated into chat (security-relevant or must-not-be-missed). */
    public static void important(Player player, Component message) {
        deliver(() -> CoreBridge.send(player, message, false, true), () -> player.sendActionBar(message));
    }

    /**
     * A title-style notice. LoveNotify takes one component, so main and sub are joined; the player's
     * settings decide whether it is shown as a title, an action bar or chat. Without LoveCore the
     * real title with subtitle is shown.
     */
    public static void title(Player player, Component main, Component sub) {
        Component joined = main.append(Component.space()).append(sub);
        deliver(() -> CoreBridge.send(player, joined, true, false), () -> player.showTitle(Title.title(main, sub,
                Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(3), Duration.ofMillis(500)))));
    }

    /**
     * Runs {@code viaCore}; if it reports no delivery, or LoveCore's classes are missing, runs
     * {@code fallback}. Kept free of any LoveCore types so the fallback can be tested without it.
     */
    static void deliver(BooleanSupplier viaCore, Runnable fallback) {
        try {
            if (viaCore.getAsBoolean()) return;
        } catch (NoClassDefFoundError | NoSuchMethodError ignored) {
            // LoveCore absent or built against another API version
        }
        fallback.run();
    }
}
