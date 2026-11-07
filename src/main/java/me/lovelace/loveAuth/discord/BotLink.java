package me.lovelace.loveAuth.discord;

import java.util.Optional;

/** The link that opens the bot's Discord profile, from which a player starts a private chat with it. */
final class BotLink {

    private BotLink() {
    }

    /**
     * @param fromCore the id the shared bot reports (null when LoveCore is older or has no token)
     * @param fromConfig {@code discord.bot-user-id}, used when the core does not know the id
     * @return {@code https://discord.com/users/<id>}, or empty when no valid id is known
     */
    static Optional<String> profileUrl(String fromCore, String fromConfig) {
        String id = valid(fromCore) ? fromCore.trim() : valid(fromConfig) ? fromConfig.trim() : null;
        return Optional.ofNullable(id).map(value -> "https://discord.com/users/" + value);
    }

    private static boolean valid(String id) {
        return id != null && id.trim().matches("[0-9]{5,25}");
    }
}
