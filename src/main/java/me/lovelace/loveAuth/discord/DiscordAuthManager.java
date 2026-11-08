package me.lovelace.loveAuth.discord;

import dev.lovelace.lovecore.api.discord.*;

import me.lovelace.loveAuth.LoveAuth;
import me.lovelace.loveAuth.auth.AuthManager;
import me.lovelace.loveAuth.config.ConfigManager;
import me.lovelace.loveAuth.database.DatabaseManager;
import me.lovelace.loveAuth.lang.LangManager;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/**
 * Authentication adapter over LoveCore's shared bot. No second Discord client or JDA classloader.
 */
public final class DiscordAuthManager implements DiscordService {
    private final LoveAuth plugin;
    private final ConfigManager config;
    private final LangManager lang;
    private final DatabaseManager database;
    private final AuthManager auth;
    private final SecureRandom random = new SecureRandom();
    private final LinkCodes pendingLinks = new LinkCodes();
    private final Map<UUID, String> pendingPasswordHashes = new ConcurrentHashMap<>();
    private final Map<UUID, String> linkedDiscordCache = new ConcurrentHashMap<>();
    private final Map<String, UUID> linkedPlayerCache = new ConcurrentHashMap<>();
    private final Set<CompletableFuture<?>> mainRequests = ConcurrentHashMap.newKeySet();
    private volatile boolean stopping;
    private volatile long generation;
    private volatile DiscordService coreTransport;
    private volatile CoreAuthMenu coreMenu;

    public DiscordAuthManager(
            LoveAuth plugin,
            ConfigManager config,
            LangManager lang,
            DatabaseManager database,
            AuthManager auth) {
        this.plugin = plugin;
        this.config = config;
        this.lang = lang;
        this.database = database;
        this.auth = auth;
    }

    public synchronized void initialize() {
        if (coreMenu != null) shutdown();
        stopping = false;
        generation++;
        if (!config.isDiscordEnabled()) {
            plugin.getLogManager().infoKey("log.discord-disabled");
            return;
        }
        var shared = dev.lovelace.lovecore.api.LoveCore.service(DiscordService.class);
        if (shared.isPresent() && !shared.get().isEnabled()) {
            try {
                shared.get()
                        .getClass()
                        .getMethod(
                                "adoptAuthBot",
                                String.class,
                                String.class,
                                String.class,
                                String.class)
                        .invoke(
                                shared.get(),
                                config.getDiscordBotToken(),
                                config.getDiscordGuildId(),
                                config.getDiscordSupportCategoryId(),
                                config.getDiscordAppealsCategoryId());
            } catch (ReflectiveOperationException e) {
                plugin.getLogger().warning("Для переноса Discord-бота обновите LoveCore");
            }
        }
        if (shared.isEmpty() || shared.get() == this || !shared.get().isEnabled()) {
            plugin.getLogger()
                    .warning(
                            "Discord авторизации ожидает активный общий бот LoveCore. Настройте"
                                + " discord в LoveCore.");
            return;
        }
        coreTransport = shared.get();
        coreMenu = new CoreAuthMenu(plugin, this, database, auth, coreTransport);
        try {
            coreMenu.start();
        } catch (RuntimeException failure) {
            shutdown();
            throw failure;
        }
        plugin.getLogger().info("Авторизация подключена к общему Discord-боту LoveCore.");
    }

    public boolean isEnabled() {
        return !stopping && coreTransport != null && coreTransport.isEnabled();
    }

    public void startBinding(Player player) {
        if (!isEnabled()) {
            lang.send(player, "discord.not-enabled");
            return;
        }
        String code = registerPendingLinkCode(player.getUniqueId());
        // The menu stays open otherwise and an unauthenticated player cannot reach the chat where the code is.
        // A plugin-initiated close is ignored by GuiCloseListener (no kick, no reopen).
        player.closeInventory();
        // The message right behind closeInventory() can be dropped by the client; send it a tick later
        // (same race GuiManager.awaitInput works around).
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;
            net.kyori.adventure.text.Component msg =
                    net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                            .deserialize(lang.get("discord.bind-instructions", Map.of("code", code)));
            player.sendMessage(msg);
            sendBotLink(player);
            if (showsMenuReturn(auth.isAuthenticated(player.getUniqueId()))) lang.send(player, "discord.bind-back");
        }, 1L);
    }

    /** A player who has not logged in has no other way back to the auth menu than the command. */
    static boolean showsMenuReturn(boolean authenticated) {
        return !authenticated;
    }

    /** The link to the bot's profile (private chat), asked from the shared bot, else from the config. */
    Optional<String> botProfileUrl() {
        String fromCore = null;
        DiscordService core = coreTransport;
        if (core != null) {
            try {
                // Reflection: an older LoveCore API has no getBotUserId().
                Object id = core.getClass().getMethod("getBotUserId").invoke(core);
                fromCore = id instanceof String text ? text : null;
            } catch (ReflectiveOperationException olderCore) {
                // fall back to discord.bot-user-id
            }
        }
        return BotLink.profileUrl(fromCore, config.getDiscordBotUserId());
    }

    /** Sends the clickable "open the private chat with the bot" line; nothing when the bot id is unknown. */
    void sendBotLink(Player player) {
        botProfileUrl().ifPresent(url -> lang.send(player, "discord.bot-link", Map.of("url", url)));
    }

    private String registerPendingLinkCode(UUID uuid) {
        if (stopping) throw new IllegalStateException("Discord authentication is stopped");
        return pendingLinks.issue(uuid);
    }

    void cacheLink(UUID uuid, String discordId) {
        String prevDiscord = linkedDiscordCache.put(uuid, discordId);
        if (prevDiscord != null && !prevDiscord.equals(discordId))
            linkedPlayerCache.remove(prevDiscord);
        linkedPlayerCache.put(discordId, uuid);
    }

    private void uncacheLink(UUID uuid) {
        String prev = linkedDiscordCache.remove(uuid);
        if (prev != null) linkedPlayerCache.remove(prev);
    }

    public void sendPasswordChangeConfirmation(Player player, String passwordHash) {
        if (!isEnabled()) {
            lang.send(player, "discord.not-enabled");
            return;
        }
        pendingPasswordHashes.put(player.getUniqueId(), passwordHash);
        // Запрос живёт столько же, сколько само сообщение с кнопками в Discord.
        UUID uuid = player.getUniqueId();
        plugin.getServer()
                .getAsyncScheduler()
                .runDelayed(
                        plugin,
                        t -> pendingPasswordHashes.remove(uuid, passwordHash),
                        2,
                        TimeUnit.MINUTES);
        sendConfirmation(player, "PASSWORD_CHANGE");
    }

    private CompletableFuture<Boolean> applyPendingPasswordChange(UUID uuid) {
        String hash = pendingPasswordHashes.remove(uuid);
        if (hash == null) return CompletableFuture.completedFuture(false);
        return auth.applyPasswordHash(uuid, hash).thenApply(ok -> true);
    }

    public Optional<String> getLinkedDiscordId(UUID playerUuid) {
        if (stopping || playerUuid == null) return Optional.empty();
        long epoch = generation;
        String cached = linkedDiscordCache.get(playerUuid);
        if (cached == null) {
            // Не блокируем вызывающего (интерфейс синхронный) — просто прогреваем кэш на будущее.
            database.findPlayer(playerUuid)
                    .thenAccept(
                            rec -> {
                                if (!stopping
                                        && generation == epoch
                                        && rec.isPresent()
                                        && rec.get().hasDiscord())
                                    cacheLink(playerUuid, rec.get().discordId());
                            });
        }
        return Optional.ofNullable(cached);
    }

    public Optional<UUID> getLinkedPlayer(String discordId) {
        if (stopping || discordId == null || discordId.isBlank()) return Optional.empty();
        long epoch = generation;
        UUID cached = linkedPlayerCache.get(discordId);
        if (cached == null) {
            database.findPlayerByDiscordId(discordId)
                    .thenAccept(
                            rec -> {
                                if (!stopping && generation == epoch)
                                    rec.ifPresent(r -> cacheLink(r.uuid(), discordId));
                            });
        }
        return Optional.ofNullable(cached);
    }

    public String generateLinkCode(UUID playerUuid) {
        return registerPendingLinkCode(playerUuid);
    }

    String corePasswordSnapshot(UUID uuid) {
        return pendingPasswordHashes.get(uuid);
    }

    CompletableFuture<Boolean> applyCorePassword(UUID uuid, String expected) {
        if (expected == null || !pendingPasswordHashes.remove(uuid, expected))
            return CompletableFuture.completedFuture(false);
        return auth.applyPasswordHash(uuid, expected).thenApply(ignored -> true);
    }

    void prepareCorePassword(UUID uuid, String hash) {
        pendingPasswordHashes.put(uuid, hash);
        plugin.getServer()
                .getAsyncScheduler()
                .runDelayed(
                        plugin,
                        t -> pendingPasswordHashes.remove(uuid, hash),
                        120,
                        TimeUnit.SECONDS);
    }

    CompletableFuture<Boolean> completeCoreLink(String discordId, String code) {
        if (stopping || discordId == null || discordId.isBlank())
            return CompletableFuture.completedFuture(false);
        UUID uuid = pendingLinks.consume(code);
        if (uuid == null) return CompletableFuture.completedFuture(false);
        return database.findPlayerByDiscordId(discordId)
                .thenCompose(
                        existing -> {
                            if (existing.isPresent() && !existing.get().uuid().equals(uuid))
                                return CompletableFuture.completedFuture(false);
                            return database.setDiscordId(uuid, discordId)
                                    .thenCompose(
                                            v ->
                                                    onMain(
                                                            () -> {
                                                                cacheLink(uuid, discordId);
                                                                syncWithLoveCore(uuid, discordId);
                                                                Player p = Bukkit.getPlayer(uuid);
                                                                if (p != null) {
                                                                    lang.send(
                                                                            p,
                                                                            "discord.bind-success");
                                                                    auth.markAuthenticated(p, true);
                                                                }
                                                                return true;
                                                            }));
                        });
    }

    public boolean completeLink(String discordId, String code) {
        if (code == null || discordId == null || discordId.isBlank()) return false;
        if (!pendingLinks.contains(code)) return false;
        completeCoreLink(discordId, code)
                .exceptionally(
                        error -> {
                            plugin.getLogger()
                                    .warning(
                                            "Привязка Discord не завершена; повторите с новым"
                                                + " кодом");
                            return false;
                        });
        return true;
    }

    public void unlink(UUID playerUuid) {
        if (playerUuid == null) return;
        database.setDiscordId(playerUuid, null)
                .thenRun(
                        () -> {
                            uncacheLink(playerUuid);
                            syncWithLoveCore(playerUuid, null);
                        });
    }

    public void setLink(UUID playerUuid, String discordId) {
        if (playerUuid == null) return;
        if (discordId == null || discordId.isBlank()) {
            unlink(playerUuid);
            return;
        }
        database.setDiscordId(playerUuid, discordId)
                .thenRun(
                        () -> {
                            cacheLink(playerUuid, discordId);
                            syncWithLoveCore(playerUuid, discordId);
                        });
    }

    public CompletableFuture<java.util.Map<String, Object>> getDiscordLoginStatus(String owner) {
        if (stopping)
            return CompletableFuture.failedFuture(
                    new java.util.concurrent.CancellationException("plugin-disabled"));
        return database.findPlayerByDiscordId(owner)
                .thenCompose(
                        record -> {
                            if (record.isEmpty())
                                return CompletableFuture.completedFuture(
                                        java.util.Map.of("available", true, "linked", false));
                            var account = record.get();
                            return onMain(
                                    () -> {
                                        var player = Bukkit.getPlayer(account.uuid());
                                        boolean online = player != null && player.isOnline();
                                        boolean pending =
                                                coreMenu != null
                                                        && coreMenu.pendingLogin(
                                                                account.uuid(), owner);
                                        return LoginHelpSnapshot.of(
                                                account.uuid(),
                                                account.username(),
                                                account.locked(),
                                                online,
                                                auth.isAuthenticated(account.uuid()),
                                                pending);
                                    });
                        });
    }

    <T> CompletableFuture<T> onMain(Supplier<T> task) {
        long epoch = generation;
        CompletableFuture<T> future = new CompletableFuture<>();
        mainRequests.add(future);
        future.whenComplete((v, e) -> mainRequests.remove(future));
        if (stopping) {
            future.completeExceptionally(
                    new java.util.concurrent.CancellationException("plugin-disabled"));
            return future;
        }
        try {
            Bukkit.getScheduler()
                    .runTask(
                            plugin,
                            () -> {
                                if (future.isDone() || stopping || generation != epoch) {
                                    future.cancel(false);
                                    return;
                                }
                                try {
                                    future.complete(task.get());
                                } catch (Throwable t) {
                                    future.completeExceptionally(t);
                                }
                            });
        } catch (Exception e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    private void runMain(Runnable task) {
        if (stopping) return;
        long epoch = generation;
        try {
            Bukkit.getScheduler()
                    .runTask(
                            plugin,
                            () -> {
                                if (!stopping && generation == epoch) task.run();
                            });
        } catch (Exception ignored) {
            // Plugin is being disabled; nothing left to do on the main thread.
        }
    }

    CompletableFuture<String> runConfirmed(DatabaseManager.PlayerRecord rec, String action) {
        UUID uuid = rec.uuid();
        String name = rec.username();
        switch (action) {
            case "UNLINK":
                return database.setDiscordId(uuid, null)
                        .thenApply(
                                v -> {
                                    uncacheLink(uuid);
                                    syncWithLoveCore(uuid, null);
                                    plugin.getLogManager()
                                            .database(uuid, "DISCORD_UNLINK", name, "discord");
                                    notifyPlayer(uuid, "discord.unlinked");
                                    return lang.plain("discord.unlinked-dm");
                                });
            case "REMOVE_PASSWORD":
                return database.setPasswordEnabled(uuid, false)
                        .thenApply(
                                v -> {
                                    plugin.getLogManager()
                                            .database(uuid, "PASSWORD_DELETE", name, "discord");
                                    notifyPlayer(uuid, "commands.password-removed");
                                    return lang.plain("discord.password-deleted-dm");
                                });
            case "PASSWORD_CHANGE":
                return applyPendingPasswordChange(uuid)
                        .thenApply(
                                applied ->
                                        lang.plain(
                                                applied
                                                        ? "discord.password-changed-dm"
                                                        : "discord.login-expired"));
            case "LOCK_ACCOUNT":
                return database.setLocked(uuid, true)
                        .thenApply(
                                v -> {
                                    plugin.getLogManager()
                                            .database(uuid, "MANUAL_LOCK", name, "discord");
                                    runMain(
                                            () -> {
                                                Player p = Bukkit.getPlayer(uuid);
                                                if (p != null) {
                                                    lang.send(p, "discord.account-locked-self");
                                                    p.kick(lang.component("block.account-locked"));
                                                }
                                            });
                                    return lang.plain("discord.locked-dm");
                                });
            default:
                return CompletableFuture.completedFuture(lang.plain("discord.login-expired"));
        }
    }

    private void notifyPlayer(UUID uuid, String key) {
        runMain(
                () -> {
                    Player p = Bukkit.getPlayer(uuid);
                    if (p != null) lang.send(p, key);
                });
    }

    public void handleBotUnlockCommand(String d) {
        database.findPlayerByDiscordId(d)
                .thenAccept(
                        r -> {
                            if (r.isPresent()) database.setLocked(r.get().uuid(), false);
                        });
    }

    public void syncWithLoveCore(UUID uuid, String discordId) {
        if (uuid == null) return;
        try {
            if (Bukkit.getPluginManager().isPluginEnabled("LoveCore")) {
                dev.lovelace.lovecore.api.LoveCore.service(
                                dev.lovelace.lovecore.api.discord.DiscordService.class)
                        .ifPresent(
                                core -> {
                                    if (core == this || stopping) return;
                                    if (discordId != null && !discordId.isBlank()) {
                                        core.setLink(uuid, discordId);
                                    } else {
                                        core.unlink(uuid);
                                    }
                                });
            }
        } catch (Throwable ignored) {
        }
    }

    public synchronized void shutdown() {
        stopping = true;
        generation++;
        mainRequests.forEach(
                f -> f.completeExceptionally(new CancellationException("plugin-disabled")));
        mainRequests.clear();
        pendingLinks.clear();
        pendingPasswordHashes.clear();
        linkedDiscordCache.clear();
        linkedPlayerCache.clear();
        CoreAuthMenu menu = coreMenu;
        coreMenu = null;
        if (menu != null) menu.close();
        coreTransport = null;
    }

    public void clearPendingLogin(UUID uuid) {
        var menu = coreMenu;
        if (menu != null) menu.clear(uuid);
    }

    public CompletableFuture<Boolean> requestDiscordLogin(Player player) {
        var menu = coreMenu;
        return !stopping && menu != null
                ? menu.login(player)
                : CompletableFuture.completedFuture(false);
    }

    public void sendConfirmation(Player player, String action) {
        var menu = coreMenu;
        if (!stopping && menu != null) menu.confirmation(player.getUniqueId(), action);
        else lang.send(player, "discord.not-enabled");
    }

    public void handleBotLinkCommand(String code, String discordId) {
        completeLink(discordId, code);
    }

    public CompletableFuture<Boolean> sendDirectMessage(
            String user, String message, DiscordEmbed embed) {
        var service = coreTransport;
        return !stopping && service != null
                ? service.sendDirectMessage(user, message, embed)
                : CompletableFuture.completedFuture(false);
    }

    public CompletableFuture<Boolean> sendChannelMessage(
            String channel, String message, DiscordEmbed embed) {
        var service = coreTransport;
        return !stopping && service != null
                ? service.sendChannelMessage(channel, message, embed)
                : CompletableFuture.completedFuture(false);
    }

    public CompletableFuture<String> createTicketChannel(
            TicketType type, String id, String player, String reason) {
        var service = coreTransport;
        return !stopping && service != null
                ? service.createTicketChannel(type, id, player, reason)
                : CompletableFuture.completedFuture(null);
    }

    public CompletableFuture<Boolean> closeTicketChannel(String channel, String reason) {
        var service = coreTransport;
        return !stopping && service != null
                ? service.closeTicketChannel(channel, reason)
                : CompletableFuture.completedFuture(false);
    }

    public void registerTicketMessageListener(TicketMessageListener listener) {
        var service = coreTransport;
        if (!stopping && service != null) service.registerTicketMessageListener(listener);
    }

    public void unregisterTicketMessageListener(TicketMessageListener listener) {
        var service = coreTransport;
        if (service != null) service.unregisterTicketMessageListener(listener);
    }
}
