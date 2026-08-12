package me.lovelace.loveAuth.integration;

import dev.lovelace.lovecore.api.auth.AuthOracle;
import me.lovelace.loveAuth.LoveAuth;

import java.util.UUID;

/**
 * Реализация {@link AuthOracle} для LoveCore — тонкая обёртка над
 * {@link me.lovelace.loveAuth.auth.AuthManager#isAuthenticated(UUID)}.
 */
public final class LoveAuthOracle implements AuthOracle {

    private final LoveAuth plugin;

    public LoveAuthOracle(LoveAuth plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean isAuthenticated(UUID playerId) {
        return plugin.getAuthManager().isAuthenticated(playerId);
    }
}
