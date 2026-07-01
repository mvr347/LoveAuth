package me.lovelace.loveAuth.auth;

import me.lovelace.loveAuth.LoveAuth;
import me.lovelace.loveAuth.config.ConfigManager;
import me.lovelace.loveAuth.database.DatabaseManager;
import me.lovelace.loveAuth.input.InputMethod;
import me.lovelace.loveAuth.lang.LangManager;
import me.lovelace.loveAuth.limbo.LimboManager;
import me.lovelace.loveAuth.security.SecurityUtils;
import me.lovelace.loveAuth.session.SessionManager;
import me.lovelace.loveAuth.util.LogManager;
import me.lovelace.loveAuth.util.SoundUtils;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import javax.crypto.SecretKey;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public final class AuthManager {
    private final LoveAuth plugin;
    private final ConfigManager config;
    private final LangManager lang;
    private final DatabaseManager database;
    private final SessionManager sessionManager;
    private final BruteForceProtection bruteForce;
    private final LimboManager limboManager;
    private final LogManager log;
    private final SecretKey masterKey;
    private final String pepper;
    
    private final Set<UUID> authenticated = ConcurrentHashMap.newKeySet();
    private final Set<UUID> registeredCache = ConcurrentHashMap.newKeySet();
    private final Set<String> registrationLock = ConcurrentHashMap.newKeySet();
    private final Map<UUID, BukkitTask> timeoutTasks = new ConcurrentHashMap<>();
    
    private static final Pattern PASSWORD_PATTERN = Pattern.compile("^[a-zA-Z0-9._\\-!@#$%^&*()]{3,25}$");

    public AuthManager(LoveAuth plugin, ConfigManager config, LangManager lang, DatabaseManager database, SessionManager sessionManager, BruteForceProtection bruteForce, LimboManager limboManager, LogManager log, SecretKey masterKey, String pepper) {
        this.plugin = plugin; this.config = config; this.lang = lang; this.database = database; this.sessionManager = sessionManager; this.bruteForce = bruteForce; this.limboManager = limboManager; this.log = log; this.masterKey = masterKey; this.pepper = pepper;
    }

    public LoveAuth getPlugin() { return plugin; }

    public void handleJoin(Player player) {
        try {
            String ip = getIp(player);
            if (!plugin.getRateLimiter().tryConsume(ip)) {
                lang.send(player, "auth.rate-limited");
                return;
            }
            database.findPlayer(player.getUniqueId()).thenAccept(record -> Bukkit.getScheduler().runTask(plugin, () -> {
                try {
                    if (!player.isOnline()) return;
                    if (record.isPresent()) {
                        handleKnownPlayer(player, record.get(), ip);
                    } else {
                        handleFirstJoin(player);
                    }
                } catch (Exception e) {
                    log.errorKey("log.database-error", Map.of("message", e.getMessage() != null ? e.getMessage() : "Auth error"), e);
                    if (player.isOnline()) player.kick(lang.component("kick.auth-error"));
                }
            })).exceptionally(error -> {
                log.errorKey("log.database-error", Map.of("message", error.getMessage() != null ? error.getMessage() : "DB Error"), error);
                if (player.isOnline()) Bukkit.getScheduler().runTask(plugin, () -> player.kick(lang.component("kick.auth-error")));
                return null;
            });
        } catch (Exception e) {
            log.errorKey("log.database-error", Map.of("message", e.getMessage() != null ? e.getMessage() : "Auth error"), e);
            if (player.isOnline()) player.kick(lang.component("kick.auth-error"));
        }
    }

    private void handleKnownPlayer(Player player, DatabaseManager.PlayerRecord record, String ip) {
        registeredCache.add(player.getUniqueId());
        sessionManager.isValid(player.getUniqueId(), ip).thenAccept(valid -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;
            if (valid) {
                markAuthenticated(player, true);
                lang.send(player, "auth.session-restored");
                lang.sendActionBar(player, "actionbar.session-active", Map.of("expires", "7d"));
                log.database(player.getUniqueId(), "SESSION_RESTORE", player.getName(), ip);
                return;
            }
            limboManager.sendToLimbo(player);
            startTimeout(player);

            // Opening an inventory in the same tick as the limbo teleport is unreliable -
            // the client is still processing the dimension-change/respawn packet and may
            // silently ignore the open-screen packet. Defer by a tick so the teleport has
            // fully landed client-side before any GUI is shown.
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!player.isOnline()) return;

                if (config.isPremiumSkipEnabled() && isPremium(player)) {
                    boolean hasPassword = record.hasPassword() && record.passwordEnabled();
                    boolean hasDiscord = record.hasDiscord();
                    if (!hasPassword && !hasDiscord) {
                        markAuthenticated(player, true);
                        lang.send(player, "auth.premium-skip");
                        lang.sendActionBar(player, "actionbar.premium-skip", Map.of());
                        log.database(player.getUniqueId(), "PREMIUM_SKIP", player.getName(), ip);
                        return;
                    }
                }

                boolean hasPassword = record.hasPassword() && record.passwordEnabled();
                boolean hasDiscord = record.hasDiscord();

                if (!hasPassword && hasDiscord) {
                    plugin.getDiscordAuthManager().requestDiscordLogin(player)
                        .thenAccept(sent -> Bukkit.getScheduler().runTask(plugin, () -> {
                            if (!player.isOnline()) return;
                            if (!sent) {
                                plugin.getGuiManager().openAuthMethod(player);
                            }
                        }));
                } else {
                    plugin.getGuiManager().openAuthMethod(player);
                }
            }, 1L);
        }));
    }

    private void handleFirstJoin(Player player) {
        limboManager.sendToLimbo(player);
        startTimeout(player);

        // See the comment in handleKnownPlayer: defer the GUI open by a tick so it
        // doesn't race the limbo teleport's dimension-change packet.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;
            if (config.isPremiumSkipEnabled() && isPremium(player)) {
                database.createPlayer(player.getUniqueId(), player.getName(), true, config.getDefaultInputMethod())
                    .thenRun(() -> registeredCache.add(player.getUniqueId()));
                plugin.getGuiManager().openPremiumWelcome(player);
            } else {
                plugin.getGuiManager().openRegister(player);
            }
        }, 1L);
    }

    public boolean isPremium(Player player) {
        return player.getUniqueId().version() == 4;
    }

    public CompletableFuture<Boolean> login(Player player, String password) {
        if (isAuthenticated(player.getUniqueId())) return CompletableFuture.completedFuture(true);
        String ip = getIp(player);
        return database.findPlayer(player.getUniqueId()).thenCompose(record -> {
            if (record.isEmpty()) return CompletableFuture.completedFuture(false);
            DatabaseManager.PlayerRecord pr = record.get();
            return supplyAsync(() -> SecurityUtils.verifyPassword(password, pr.passwordHash(), pepper)).thenCompose(valid -> {
                if (valid) return handleLoginSuccess(player, ip).thenApply(unused -> true);
                return handleLoginFailure(player, ip).thenApply(unused -> false);
            });
        });
    }

    private CompletableFuture<Void> handleLoginSuccess(Player player, String ip) {
        return bruteForce.recordSuccess(ip)
                .thenCompose(unused -> sessionManager.create(player.getUniqueId(), ip))
                .thenCompose(unused -> database.updateLastLogin(player.getUniqueId(), ip))
                .thenRun(() -> Bukkit.getScheduler().runTask(plugin, () -> {
                    markAuthenticated(player, false);
                    lang.send(player, "login.success");
                    lang.showTitle(player, "title.login-success-main", "title.login-success-sub");
                    log.database(player.getUniqueId(), "LOGIN_SUCCESS", player.getName(), ip);
                    SoundUtils.success(player);
                }));
    }

    private CompletableFuture<Void> handleLoginFailure(Player player, String ip) {
        return bruteForce.recordFailure(player, player.getUniqueId(), ip).thenAccept(result -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (result.type() == BruteForceProtection.FailureType.FAILED) {
                lang.send(player, "login.fail", Map.of("attempts", Integer.toString(result.remainingAttempts())));
                log.database(player.getUniqueId(), "LOGIN_FAIL", player.getName(), ip);
                SoundUtils.error(player);
                if (player.isOnline()) requestPasswordLogin(player);
            } else if (result.type() == BruteForceProtection.FailureType.IP_BLOCKED) {
                player.kick(lang.component("block.ip-locked", Map.of("minutes", Long.toString(result.lockMinutes()))));
            } else {
                player.kick(lang.component("block.account-locked"));
            }
        }));
    }

    public CompletableFuture<Boolean> register(Player player, String password) {
        if (!validatePassword(player, password)) return CompletableFuture.completedFuture(false);
        if (isRegisteredCached(player.getUniqueId())) return CompletableFuture.completedFuture(false);
        String name = player.getName().toLowerCase();
        if (!registrationLock.add(name)) return CompletableFuture.completedFuture(false);

        String ip = getIp(player);
        return database.isRegistered(player.getUniqueId()).thenCompose(registered -> {
            if (registered) { registrationLock.remove(name); return CompletableFuture.completedFuture(false); }
            return supplyAsync(() -> SecurityUtils.hashPassword(password, pepper, config.getBcryptStrength()))
                .thenCompose(hash -> database.registerPlayer(player.getUniqueId(), player.getName(), hash, false, config.getDefaultInputMethod()))
                .thenCompose(v -> database.updateLastLogin(player.getUniqueId(), ip))
                .thenRun(() -> {
                    registrationLock.remove(name);
                    registeredCache.add(player.getUniqueId());
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        markAuthenticated(player, true);
                        lang.showTitle(player, "title.register-success-main", "title.register-success-sub");
                        log.database(player.getUniqueId(), "REGISTER_SUCCESS", player.getName(), ip);
                        SoundUtils.success(player);
                        if (config.isRegisterSpawnEnabled()) {
                            World spawnWorld = Bukkit.getWorld(config.getRegisterSpawnWorld());
                            if (spawnWorld != null) player.teleport(spawnWorld.getSpawnLocation());
                        }
                    });
                }).thenApply(v -> true);
        }).exceptionally(e -> { registrationLock.remove(name); return false; });
    }

    public boolean validatePassword(Player player, String password) {
        if (password == null || password.length() < 3 || password.length() > 25) {
            lang.sendActionBar(player, "register.password-length", Map.of());
            SoundUtils.error(player);
            return false;
        }
        if (!PASSWORD_PATTERN.matcher(password).matches()) {
            lang.sendActionBar(player, "register.password-invalid-chars", Map.of());
            SoundUtils.error(player);
            return false;
        }
        return true;
    }

    public void markAuthenticated(Player player, boolean createSession) {
        authenticated.add(player.getUniqueId());
        cancelTimeout(player.getUniqueId());
        limboManager.restore(player);
        if (createSession) sessionManager.create(player.getUniqueId(), getIp(player));
    }

    public void cleanup(UUID uuid) {
        authenticated.remove(uuid);
        registeredCache.remove(uuid);
        cancelTimeout(uuid);
        Player p = Bukkit.getPlayer(uuid);
        if (p != null) registrationLock.remove(p.getName().toLowerCase());
    }

    private void startTimeout(Player player) {
        cancelTimeout(player.getUniqueId());
        timeoutTasks.put(player.getUniqueId(), Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline() && !isAuthenticated(player.getUniqueId())) player.kick(lang.component("kick.timeout"));
        }, config.getAuthTimeoutSeconds() * 20L));
    }

    private void cancelTimeout(UUID uuid) {
        BukkitTask task = timeoutTasks.remove(uuid);
        if (task != null) task.cancel();
    }

    public boolean isAuthenticated(UUID uuid) { return authenticated.contains(uuid); }
    public boolean isRegisteredCached(UUID uuid) { return registeredCache.contains(uuid); }
    public SessionManager getSessionManager() { return sessionManager; }
    public String getIp(Player player) { return player.getAddress().getAddress().getHostAddress(); }
    private <T> CompletableFuture<T> supplyAsync(java.util.concurrent.Callable<T> s) {
        CompletableFuture<T> f = new CompletableFuture<>();
        plugin.getServer().getAsyncScheduler().runNow(plugin, t -> { try { f.complete(s.call()); } catch (Exception e) { f.completeExceptionally(e); } });
        return f;
    }

    public void requestPasswordLogin(Player player) {
        if (isAuthenticated(player.getUniqueId())) return;
        resolveInputMethod(player).thenAccept(m -> Bukkit.getScheduler().runTask(plugin, () -> 
            plugin.getGuiManager().awaitInput(player, m, "login.prompt-" + m.name().toLowerCase(), p -> login(player, p))));
    }

    public void requestRegistration(Player player) {
        if (isRegisteredCached(player.getUniqueId())) return;
        resolveInputMethod(player).thenAccept(m -> Bukkit.getScheduler().runTask(plugin, () -> 
            plugin.getGuiManager().awaitInput(player, m, "register.prompt-password", p1 -> {
                if (!validatePassword(player, p1)) {
                    requestRegistration(player);
                    return;
                }
                plugin.getGuiManager().awaitInput(player, m, "register.prompt-confirm", p2 -> {
                    if (p1.equals(p2)) register(player, p1);
                    else {
                        lang.sendActionBar(player, "register.password-mismatch", Map.of());
                        SoundUtils.error(player);
                        if (player.isOnline()) requestRegistration(player);
                    }
                });
            })));
    }

    public CompletableFuture<InputMethod> resolveInputMethod(Player player) {
        return database.findPlayer(player.getUniqueId()).thenApply(r -> r.map(DatabaseManager.PlayerRecord::inputMethod).orElse(config.getDefaultInputMethod()));
    }
    
    public CompletableFuture<Void> switchInputMethod(Player player) {
        return resolveInputMethod(player).thenCompose(curr -> {
            InputMethod next = curr == InputMethod.CHAT ? InputMethod.SIGN : InputMethod.CHAT;
            return database.setInputMethod(player.getUniqueId(), next).thenRun(() -> Bukkit.getScheduler().runTask(plugin, () -> 
                lang.send(player, "commands.input-method-changed", Map.of("method", lang.plain(next == InputMethod.CHAT ? "gui.account.input-chat" : "gui.account.input-sign")))));
        });
    }

    public CompletableFuture<Void> forceLogout(UUID uuid) { authenticated.remove(uuid); return sessionManager.invalidate(uuid); }
    public CompletableFuture<Boolean> forceLogin(UUID uuid) { 
        Player p = Bukkit.getPlayer(uuid); 
        if (p != null) Bukkit.getScheduler().runTask(plugin, () -> markAuthenticated(p, true));
        return CompletableFuture.completedFuture(p != null);
    }
    public CompletableFuture<Void> forceRegister(UUID uuid, String pass) {
        return supplyAsync(() -> SecurityUtils.hashPassword(pass, pepper, config.getBcryptStrength()))
            .thenCompose(h -> database.registerPlayer(uuid, "Unknown", h, false, config.getDefaultInputMethod()))
            .thenRun(() -> registeredCache.add(uuid));
    }

    public void requestPasswordChange(Player player) {
        resolveInputMethod(player).thenAccept(m -> Bukkit.getScheduler().runTask(plugin, () -> {
            plugin.getGuiManager().awaitInput(player, m, "register.prompt-password", p -> {
                if (!validatePassword(player, p)) {
                    requestPasswordChange(player);
                    return;
                }
                database.findPlayer(player.getUniqueId()).thenAccept(record -> {
                    boolean hasDiscord = record.map(DatabaseManager.PlayerRecord::hasDiscord).orElse(false);
                    if (hasDiscord) {
                        supplyAsync(() -> SecurityUtils.hashPassword(p, pepper)).thenAccept(hash -> Bukkit.getScheduler().runTask(plugin, () -> {
                            if (player.isOnline()) plugin.getDiscordAuthManager().sendPasswordChangeConfirmation(player, hash);
                        }));
                    } else {
                        updatePassword(player, p).thenRun(() -> {
                            if (player.isOnline()) plugin.getGuiManager().openAccount(player);
                        });
                    }
                });
            });
        }));
    }

    public CompletableFuture<Boolean> updatePassword(Player player, String pass) {
        return supplyAsync(() -> SecurityUtils.hashPassword(pass, pepper, config.getBcryptStrength()))
            .thenCompose(h -> database.updatePassword(player.getUniqueId(), h))
            .thenApply(v -> {
                log.database(player.getUniqueId(), "PASSWORD_CHANGE", player.getName(), getIp(player));
                Bukkit.getScheduler().runTask(plugin, () -> {
                    lang.sendActionBar(player, "commands.password-changed", Map.of());
                    SoundUtils.success(player);
                });
                return true;
            });
    }
}
