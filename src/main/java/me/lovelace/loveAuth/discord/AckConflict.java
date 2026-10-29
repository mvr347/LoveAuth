package me.lovelace.loveAuth.discord;

/** Discord error codes that mean "someone else already answered this interaction". */
final class AckConflict {

    /** {@code INTERACTION_ALREADY_ACKNOWLEDGED}. */
    private static final int ALREADY_ACKNOWLEDGED = 40060;

    private AckConflict() {}

    static boolean isAlreadyAcknowledged(int errorCode) {
        return errorCode == ALREADY_ACKNOWLEDGED;
    }
}
