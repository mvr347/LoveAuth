package me.lovelace.loveAuth.integration;

import dev.lovelace.lovecore.api.LoveCore;
import dev.lovelace.lovecore.api.secrets.SecretBook;

import java.util.UUID;

/**
 * Bridge to LoveCore's {@link SecretBook} (the book of secrets in LoveEssential). Looked up on every call; an older
 * LoveCore without the contract ({@link NoClassDefFoundError}) or no book simply means the find is not recorded.
 */
public final class SecretsHook {
    private SecretsHook() {
    }

    /** Marks a secret found; false when it was already found or there is no book. Main thread only. */
    public static boolean discover(UUID player, String secretId) {
        try {
            return Gateway.discover(player, secretId);
        } catch (NoClassDefFoundError missingContract) {
            return false;
        }
    }

    /** Isolated so the contract class is only resolved inside the try/catch above. */
    static final class Gateway {
        private Gateway() {
        }

        static boolean discover(UUID player, String secretId) {
            return LoveCore.service(SecretBook.class).map(book -> book.discover(player, secretId)).orElse(false);
        }
    }
}
