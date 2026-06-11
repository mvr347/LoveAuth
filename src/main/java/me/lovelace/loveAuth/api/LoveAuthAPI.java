package me.lovelace.loveAuth.api;

import me.lovelace.loveAuth.LoveAuth;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class LoveAuthAPI {
    private static LoveAuthAPI instance;
    private final LoveAuth plugin;

    public LoveAuthAPI(LoveAuth plugin) {
        this.plugin = plugin;
    }

    public static LoveAuthAPI getInstance() { return instance; }
    public static void setInstance(LoveAuthAPI api) { instance = api; }

    public boolean isAuthenticated(UUID uuid) { return plugin.getAuthManager().isAuthenticated(uuid); }
    public CompletableFuture<Boolean> isRegistered(UUID uuid) { return plugin.getDatabaseManager().isRegistered(uuid); }
    public CompletableFuture<Boolean> isLocked(UUID uuid) { return plugin.getDatabaseManager().isLocked(uuid); }
    public int getQueuePosition(UUID uuid) { return plugin.getQueueManager().getPosition(uuid); }

    public CompletableFuture<Boolean> forceLogin(UUID uuid) { return plugin.getAuthManager().forceLogin(uuid); }
    public CompletableFuture<Void> forceLogout(UUID uuid) { return plugin.getAuthManager().forceLogout(uuid); }
    public CompletableFuture<Void> forceRegister(UUID uuid, String password) { return plugin.getAuthManager().forceRegister(uuid, password); }

    public CompletableFuture<Optional<Long>> getSessionExpiry(UUID uuid) { return plugin.getSessionManager().getExpiry(uuid); }
    public CompletableFuture<Void> invalidateSession(UUID uuid) { return plugin.getSessionManager().invalidate(uuid); }
}
