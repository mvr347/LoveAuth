package me.lovelace.loveAuth.util;

import dev.lovelace.lovecore.api.LoveCore;
import dev.lovelace.lovecore.api.notify.LoveNotify;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/** The only class that touches LoveCore types; loaded lazily so a missing LoveCore cannot break {@link Notify}. */
final class CoreBridge {
    private CoreBridge() {}

    /** @return false if the LoveNotify service is not registered. */
    static boolean send(Player player, Component message, boolean asTitle, boolean important) {
        Optional<LoveNotify> service = LoveCore.service(LoveNotify.class);
        if (service.isEmpty()) return false;
        // A title request also allows the action bar, so with the default settings it lands there instead of in chat.
        Set<LoveNotify.Channel> channels = asTitle
                ? EnumSet.of(LoveNotify.Channel.TITLE, LoveNotify.Channel.ACTION_BAR)
                : EnumSet.of(LoveNotify.Channel.ACTION_BAR);
        service.get().notify(player, message, channels, important);
        return true;
    }
}
