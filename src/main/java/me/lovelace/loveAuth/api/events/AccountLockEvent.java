package me.lovelace.loveAuth.api.events;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

public final class AccountLockEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final UUID uuid;
    private final LockReason reason;

    public AccountLockEvent(Player player, UUID uuid, LockReason reason) {
        this.player = player;
        this.uuid = uuid;
        this.reason = reason;
    }

    public Player getPlayer() { return player; }
    public UUID getUuid() { return uuid; }
    public LockReason getReason() { return reason; }

    public enum LockReason { IP_BLOCKED, ACCOUNT_LOCKED }

    @Override
    public @NotNull HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
