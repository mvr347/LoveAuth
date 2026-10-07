package me.lovelace.loveAuth.listeners;

/**
 * Pure decision for an auth menu being closed by something other than the plugin itself.
 * A cross-world teleport puts the client on a loading screen that sends a close packet
 * (seen as PLAYER or UNKNOWN), which used to kick players who never touched anything.
 */
public final class AuthCloseDecision {
    private AuthCloseDecision() {}

    public enum Action { IGNORE, REOPEN, KICK }

    /**
     * @param playerReason  close reason is PLAYER
     * @param unknownReason close reason is UNKNOWN
     * @param sinceOpenMs   ms since the menu was opened, or negative if unknown
     * @param graceMs       grace window after opening during which a PLAYER close reopens
     */
    public static Action decide(boolean playerReason, boolean unknownReason, long sinceOpenMs, long graceMs) {
        if (unknownReason) return Action.REOPEN;
        if (!playerReason) return Action.IGNORE;
        if (sinceOpenMs >= 0 && sinceOpenMs < graceMs) return Action.REOPEN;
        return Action.KICK;
    }
}
