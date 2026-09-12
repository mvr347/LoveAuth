package me.lovelace.loveAuth.listeners;

import dev.lovelace.lovecore.api.tickets.TicketClosedEvent;
import dev.lovelace.lovecore.api.tickets.TicketCreatedEvent;
import dev.lovelace.lovecore.api.tickets.TicketMessageAddedEvent;
import me.lovelace.loveAuth.discord.DiscordAuthManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

/**
 * Relays LoveWebAdmin's ticket lifecycle (fired via LoveCore) into DiscordAuthManager, which
 * owns the actual JDA channel creation/relay. These events are async (fired from LoveWebAdmin's
 * Jetty handler threads, not Bukkit's main thread) - DiscordAuthManager's ticket methods are
 * written to tolerate that (JDA calls are thread-safe; nothing here touches Bukkit API directly).
 */
public final class TicketBridgeListener implements Listener {

    private final DiscordAuthManager discordAuthManager;

    public TicketBridgeListener(DiscordAuthManager discordAuthManager) {
        this.discordAuthManager = discordAuthManager;
    }

    @EventHandler
    public void onTicketCreated(TicketCreatedEvent event) {
        discordAuthManager.handleTicketCreated(event.ticketId(), event.type(), event.playerUuid(),
                event.playerName(), event.subject(), event.targetUuid(), event.targetName());
    }

    @EventHandler
    public void onTicketMessage(TicketMessageAddedEvent event) {
        discordAuthManager.handleTicketMessage(event.ticketId(), event.authorName(), event.body(), event.source());
    }

    @EventHandler
    public void onTicketClosed(TicketClosedEvent event) {
        discordAuthManager.handleTicketClosed(event.ticketId());
    }
}
