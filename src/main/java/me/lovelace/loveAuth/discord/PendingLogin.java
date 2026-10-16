package me.lovelace.loveAuth.discord;

/**
 * A Discord login request bound to the exact DM message, the IP the player connected from
 * and a random nonce embedded in the button ids. Approval is valid only if all three match.
 */
record PendingLogin(long messageId, String ip, String nonce) {

    /** Checks the clicked message and the nonce from the button id. */
    boolean matchesRequest(long clickedMessageId, String clickedNonce) {
        return messageId == clickedMessageId && nonce != null && nonce.equals(clickedNonce);
    }

    /** Checks that the player is still connected from the IP the request was made for. */
    boolean matchesIp(String currentIp) {
        return ip != null && ip.equals(currentIp);
    }
}
