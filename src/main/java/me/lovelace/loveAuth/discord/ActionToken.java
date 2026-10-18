package me.lovelace.loveAuth.discord;

import java.util.Optional;
import java.util.UUID;

/**
 * Parsed Discord component id of the form {@code kind:owner:action:nonce}.
 * <p>
 * {@code owner} is the linked player's UUID, or {@code -} when the component is not tied to an
 * account (unlinked panel, modals). {@code nonce} is a random one-time code for confirmation cards
 * and {@link #STATIC_NONCE} for reusable controls such as panel buttons.
 */
record ActionToken(String kind, UUID owner, String action, String nonce) {

    static final String NO_OWNER = "-";
    static final String STATIC_NONCE = "0";
    /** Discord rejects component ids longer than this. */
    static final int MAX_ID_LENGTH = 100;

    /** Parses a component id; never throws, a malformed id simply yields an empty result. */
    static Optional<ActionToken> parse(String id) {
        if (id == null || id.length() > MAX_ID_LENGTH) return Optional.empty();
        String[] parts = id.split(":", -1);
        if (parts.length != 4) return Optional.empty();
        String kind = parts[0];
        String action = parts[2];
        String nonce = parts[3];
        if (kind.isEmpty() || action.isEmpty() || nonce.isEmpty()) return Optional.empty();
        UUID owner = null;
        if (!NO_OWNER.equals(parts[1])) {
            try {
                owner = UUID.fromString(parts[1]);
            } catch (IllegalArgumentException ex) {
                return Optional.empty();
            }
        }
        return Optional.of(new ActionToken(kind, owner, action, nonce));
    }

    /** Serialises back into a component id. */
    String toId() {
        return kind + ":" + (owner == null ? NO_OWNER : owner.toString()) + ":" + action + ":" + nonce;
    }

    /** One-time tokens are consumed on use; static ones (panel buttons) can be clicked repeatedly. */
    boolean oneTime() {
        return !STATIC_NONCE.equals(nonce);
    }
}
