package me.lovelace.loveAuth.api.events;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

public final class PlayerAuthSuccessEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final UUID uuid;

    public PlayerAuthSuccessEvent(Player player, UUID uuid) {
        this.player = player;
        this.uuid = uuid;
    }

    public Player getPlayer() { return player; }
    public UUID getUuid() { return uuid; }

    @Override
    public @NotNull HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
