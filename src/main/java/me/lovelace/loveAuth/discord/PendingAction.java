package me.lovelace.loveAuth.discord;

import java.util.UUID;

/**
 * A confirmation card bound to the exact Discord message, the account it concerns and a random
 * one-time nonce embedded in its button ids (modelled on {@link PendingLogin}). A click is accepted
 * only if the message, owner, action and nonce all match what was issued.
 */
record PendingAction(long messageId, UUID owner, String action, String nonce) {

    /** Checks the clicked message and the parsed button id against the issued card. */
    boolean matches(long clickedMessageId, ActionToken token) {
        return token != null
            && messageId == clickedMessageId
            && owner != null && owner.equals(token.owner())
            && action != null && action.equals(token.action())
            && nonce != null && nonce.equals(token.nonce());
    }
}
