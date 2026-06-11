package me.lovelace.loveAuth.api.events;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

public final class PlayerAuthFailEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final UUID uuid;
    private final String reason;

    public PlayerAuthFailEvent(Player player, UUID uuid, String reason) {
        this.player = player;
        this.uuid = uuid;
        this.reason = reason;
    }

    public Player getPlayer() { return player; }
    public UUID getUuid() { return uuid; }
    public String getReason() { return reason; }

    @Override
    public @NotNull HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
