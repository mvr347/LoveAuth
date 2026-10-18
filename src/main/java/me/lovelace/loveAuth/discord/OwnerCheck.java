package me.lovelace.loveAuth.discord;

import java.util.UUID;

/** Decides whether a Discord user may use a button that is bound to a particular account. */
final class OwnerCheck {
    private OwnerCheck() {}

    /**
     * @param linkedPlayer the player linked to the Discord user who clicked, or null if none
     * @param tokenOwner   the player UUID embedded in the button id
     * @return true only when both are known and identical
     */
    static boolean isOwner(UUID linkedPlayer, UUID tokenOwner) {
        return linkedPlayer != null && linkedPlayer.equals(tokenOwner);
    }
}
