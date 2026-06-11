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
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import javax.crypto.SecretKey;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

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

    public AuthManager(LoveAuth plugin, ConfigManager config, LangManager lang, DatabaseManager database, SessionManager sessionManager, BruteForceProtection bruteForce, LimboManager limboManager, LogManager log, SecretKey masterKey, String pepper) {
        this.plugin = plugin; this.config = config; this.lang = lang; this.database = database; this.sessionManager = sessionManager; this.bruteForce = bruteForce; this.limboManager = limboManager; this.log = log; this.masterKey = masterKey; this.pepper = pepper;
    }

    public LoveAuth getPlugin() { return plugin; }

    public void handleJoin(Player player) {
        String ip = getIp(player);
        if (!plugin.getRateLimiter().tryConsume(ip)) {
            lang.send(player, "auth.rate-limited");
            return;
        }
        database.findPlayer(player.getUniqueId()).thenAccept(record -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;
            if (record.isPresent()) {
                handleKnownPlayer(player, record.get(), ip);
            } else {
                handleFirstJoin(player);
            }
        })).exceptionally(error -> {
            log.errorKey("log.database-error", Map.of("message", error.getMessage() != null ? error.getMessage() : "DB Error"), error);
            return null;
        });
    }

    private void handleKnownPlayer(Player player, DatabaseManager.PlayerRecord record, String ip) {
        registeredCache.add(player.getUniqueId());
        sessionManager.isValid(player.getUniqueId(), ip).thenAccept(valid -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;
            if (valid) {
                markAuthenticated(player, true);
                lang.send(player, "auth.session-restored");
                return;
            }
            limboManager.sendToLimbo(player);
            startTimeout(player);

            boolean hasPassword = record.hasPassword() && record.passwordEnabled();
            boolean hasDiscord = record.hasDiscord();

            if (!hasPassword && hasDiscord) {
                plugin.getDiscordAuthManager().requestDiscordLogin(player)
                    .thenAccept(sent -> Bukkit.getScheduler().runTask(plugin, () -> {
                        if (!sent) {
                            plugin.getGuiManager().openAuthMethod(player);
                        }
                    }));
            } else {
                plugin.getGuiManager().openAuthMethod(player);
            }
        }));
    }

    private void handleFirstJoin(Player player) {
        limboManager.sendToLimbo(player);
        startTimeout(player);
        if (config.isPremiumSkipEnabled() && isPremium(player)) {
            plugin.getGuiManager().openPremiumWelcome(player);
        } else {
            plugin.getGuiManager().openRegister(player);
        }
    }

    private boolean isPremium(Player player) {
        // Version 4 is online-mode UUID
        return player.getUniqueId().version() == 4;
    }

    public CompletableFuture<Boolean> login(Player player, String password) {
        if (isAuthenticated(player.getUniqueId())) return CompletableFuture.completedFuture(true);
        String ip = getIp(player);
        return database.findPlayer(player.getUniqueId()).thenCompose(record -> {
            if (record.isEmpty()) return CompletableFuture.completedFuture(false);
            DatabaseManager.PlayerRecord pr = record.get();
            return supplyAsync(() -> SecurityUtils.verifyPassword(password, pr.passwordHash())).thenCompose(valid -> {
                if (valid) return handleLoginSuccess(player, ip).thenApply(unused -> true);
                return handleLoginFailure(player, ip).thenApply(unused -> false);
            });
        });
    }

    private CompletableFuture<Void> handleLoginSuccess(Player player, String ip) {
        return bruteForce.recordSuccess(ip)
                .thenCompose(unused -> sessionManager.create(player.getUniqueId(), ip))
                .thenCompose(unused -> database.updateLastLogin(player.getUniqueId()))
                .thenRun(() -> Bukkit.getScheduler().runTask(plugin, () -> {
                    markAuthenticated(player, false);
                    lang.send(player, "login.success");
                }));
    }

    private CompletableFuture<Void> handleLoginFailure(Player player, String ip) {
        return bruteForce.recordFailure(player, player.getUniqueId(), ip).thenAccept(result -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (result.type() == BruteForceProtection.FailureType.FAILED) {
                lang.send(player, "login.fail", Map.of("attempts", Integer.toString(result.remainingAttempts())));
                if (player.isOnline()) requestPasswordLogin(player);
            } else {
                player.kick(lang.component("block.ip-locked"));
            }
        }));
    }

    public CompletableFuture<Boolean> register(Player player, String password) {
        if (isRegisteredCached(player.getUniqueId())) return CompletableFuture.completedFuture(false);
        String name = player.getName().toLowerCase();
        if (!registrationLock.add(name)) return CompletableFuture.completedFuture(false);

        return database.isRegistered(player.getUniqueId()).thenCompose(registered -> {
            if (registered) { registrationLock.remove(name); return CompletableFuture.completedFuture(false); }
            return supplyAsync(() -> SecurityUtils.hashPassword(password))
                .thenCompose(hash -> database.registerPlayer(player.getUniqueId(), player.getName(), hash, false, config.getDefaultInputMethod()))
                .thenRun(() -> {
                    registrationLock.remove(name);
                    registeredCache.add(player.getUniqueId());
                    Bukkit.getScheduler().runTask(plugin, () -> markAuthenticated(player, true));
                }).thenApply(v -> true);
        }).exceptionally(e -> { registrationLock.remove(name); return false; });
    }

    public void markAuthenticated(Player player, boolean createSession) {
        authenticated.add(player.getUniqueId());
        cancelTimeout(player.getUniqueId());
        limboManager.restore(player);
        if (createSession) sessionManager.create(player.getUniqueId(), getIp(player));
    }

    public void cleanup(UUID uuid) {
        authenticated.remove(uuid);
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
    private String getIp(Player player) { return player.getAddress().getAddress().getHostAddress(); }
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
                plugin.getGuiManager().awaitInput(player, m, "register.prompt-confirm", p2 -> {
                    if (p1.equals(p2)) register(player, p1);
                    else {
                        lang.send(player, "register.password-mismatch");
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
        return supplyAsync(() -> SecurityUtils.hashPassword(pass))
            .thenCompose(h -> database.registerPlayer(uuid, "Unknown", h, false, config.getDefaultInputMethod()))
            .thenRun(() -> registeredCache.add(uuid));
    }

    public void requestPasswordChange(Player player) {
        resolveInputMethod(player).thenAccept(m -> Bukkit.getScheduler().runTask(plugin, () -> {
            lang.send(player, "register.prompt-password");
            plugin.getGuiManager().awaitInput(player, m, "register.prompt-password", p -> {
                updatePassword(player, p).thenRun(() -> {
                    if (player.isOnline()) plugin.getGuiManager().openAccount(player);
                });
            });
        }));
    }

    public CompletableFuture<Boolean> updatePassword(Player player, String pass) {
        return supplyAsync(() -> SecurityUtils.hashPassword(pass))
            .thenCompose(h -> database.updatePassword(player.getUniqueId(), h))
            .thenApply(v -> { lang.send(player, "commands.password-changed"); return true; });
    }
}
