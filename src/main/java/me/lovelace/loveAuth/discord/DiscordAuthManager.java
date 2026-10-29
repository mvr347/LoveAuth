package me.lovelace.loveAuth.discord;

import dev.lovelace.lovecore.api.discord.DiscordEmbed;
import dev.lovelace.lovecore.api.discord.DiscordService;
import dev.lovelace.lovecore.api.discord.TicketMessageListener;
import dev.lovelace.lovecore.api.discord.TicketType;
import me.lovelace.loveAuth.LoveAuth;
import me.lovelace.loveAuth.auth.AuthManager;
import me.lovelace.loveAuth.config.ConfigManager;
import me.lovelace.loveAuth.database.DatabaseManager;
import me.lovelace.loveAuth.lang.LangManager;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.interactions.components.text.TextInput;
import net.dv8tion.jda.api.interactions.components.text.TextInputStyle;
import net.dv8tion.jda.api.interactions.modals.Modal;
import net.dv8tion.jda.api.interactions.modals.ModalMapping;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Помимо своего изначального назначения (вход/привязка аккаунта через Discord), этот класс
 * реализует общий {@link DiscordService} из LoveCore: когда {@code discord.tickets-bridge.enabled}
 * включён (по умолчанию), LoveAuth регистрирует себя в ServicesManager с приоритетом выше
 * собственного REST-бота LoveCore — тикеты/репорты/апелляции LoveWebAdmin начинают идти через
 * этот же (уже подключённый по gateway) Discord-бот вместо отдельного токена в LoveCore.
 */
public final class DiscordAuthManager implements DiscordService {
    private JDA jda;
    private final LoveAuth plugin;
    private final ConfigManager config;
    private final LangManager lang;
    private final DatabaseManager database;
    private final AuthManager auth;

    private final Map<String, UUID> pendingLinks = new ConcurrentHashMap<>();
    private final Map<UUID, PendingLogin> pendingLogins = new ConcurrentHashMap<>();
    // Хеш нового пароля ждёт подтверждения в Discord: в базу он попадает только
    // после нажатия «Подтвердить», иначе смену можно было бы навязать чужими руками.
    private final Map<UUID, String> pendingPasswordHashes = new ConcurrentHashMap<>();
    // Карточки подтверждения: одноразовый код -> сообщение, к которому он привязан.
    private final Map<String, PendingAction> pendingActions = new ConcurrentHashMap<>();
    private volatile BukkitTask presenceTask;
    private volatile String lastPresence;
    private final SecureRandom random = new SecureRandom();

    // --- DiscordService (мост тикетов LoveCore) ---
    // discordId в БД хранится зашифрованным и читается только асинхронно, а интерфейс
    // DiscordService требует синхронного ответа — поэтому здесь лёгкий кэш поверх БД, который
    // прогревается по мере привязок/отвязок и лениво дозаполняется в фоне при промахах, не
    // блокируя вызывающий поток.
    private final Map<UUID, String> linkedDiscordCache = new ConcurrentHashMap<>();
    private final Map<String, UUID> linkedPlayerCache = new ConcurrentHashMap<>();
    private final Map<String, String> activeTicketChannels = new ConcurrentHashMap<>(); // ticketId -> channelId
    private final Map<String, String> ticketChannelToId = new ConcurrentHashMap<>(); // channelId -> ticketId
    private final List<TicketMessageListener> ticketMessageListeners = new CopyOnWriteArrayList<>();

    public DiscordAuthManager(LoveAuth plugin, ConfigManager config, LangManager lang, DatabaseManager database, AuthManager auth) {
        this.plugin = plugin;
        this.config = config;
        this.lang = lang;
        this.database = database;
        this.auth = auth;
    }

    public void initialize() {
        if (!config.isDiscordEnabled()) {
            plugin.getLogManager().infoKey("log.discord-disabled");
            return;
        }
        String token = config.getDiscordBotToken();
        if (token.isBlank()) {
            plugin.getLogManager().warnKey("log.discord-no-token");
            return;
        }
        installAckConflictHandler();
        try {
            jda = JDABuilder.createLight(token,
                    GatewayIntent.GUILD_MESSAGES,
                    GatewayIntent.DIRECT_MESSAGES,
                    GatewayIntent.MESSAGE_CONTENT)
                .addEventListeners(new DiscordEventListener())
                .build();
            jda.awaitReady();
            plugin.getLogManager().infoKey("log.discord-ready",
                Map.of("tag", jda.getSelfUser().getAsTag()));
            registerCommands();
            lastPresence = null;
            startPresence();
        } catch (Exception e) {
            plugin.getLogManager().errorKey("log.discord-start-error",
                Map.of("message", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()), e);
            jda = null;
        }
    }

    public boolean isEnabled() { return jda != null; }

    private final java.util.concurrent.atomic.AtomicBoolean ackConflictReported = new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * Another process on the same bot token answered the interaction first, so ours is rejected with 40060.
     * That is harmless (the first answer wins) and its stack trace only hides real errors: report it once,
     * hand everything else to JDA's own default handler. The default is static, but this JDA copy is
     * relocated into LoveAuth, so no other plugin shares it.
     */
    private void installAckConflictHandler() {
        Consumer<? super Throwable> jdaDefault = net.dv8tion.jda.api.requests.RestAction.getDefaultFailure();
        net.dv8tion.jda.api.requests.RestAction.setDefaultFailure(error -> {
            if (error instanceof net.dv8tion.jda.api.exceptions.ErrorResponseException response
                    && AckConflict.isAlreadyAcknowledged(response.getErrorCode())) {
                if (ackConflictReported.compareAndSet(false, true)) {
                    plugin.getLogManager().warnKey("log.discord-ack-conflict");
                }
                return;
            }
            jdaDefault.accept(error);
        });
    }

    public void shutdown() {
        BukkitTask task = presenceTask;
        presenceTask = null;
        if (task != null) task.cancel();
        lastPresence = null;
        pendingActions.clear();
        if (jda == null) return;
        JDA toClose = jda;
        jda = null;
        toClose.shutdown();
        try {
            // Block until the gateway WebSocket and JDA's internal threads have
            // actually terminated. Bukkit closes this plugin's classloader right
            // after onDisable() returns; JDA.shutdown() alone only *requests* a
            // close and returns immediately, so those still-running threads would
            // otherwise reference now-unloaded plugin classes (e.g. the event
            // listener) and blow up with NoClassDefFoundError/WebSocket errors
            // during server shutdown.
            if (!toClose.awaitShutdown(java.time.Duration.ofSeconds(10))) {
                toClose.shutdownNow();
                toClose.awaitShutdown(java.time.Duration.ofSeconds(5));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void startBinding(Player player) {
        if (!isEnabled()) { lang.send(player, "discord.not-enabled"); return; }
        String code = registerPendingLinkCode(player.getUniqueId());
        net.kyori.adventure.text.Component msg = net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(lang.get("discord.bind-instructions", Map.of("code", code)));
        player.sendMessage(msg);
    }

    private String registerPendingLinkCode(UUID uuid) {
        String code = generateCode(8);
        pendingLinks.put(code, uuid);
        plugin.getServer().getAsyncScheduler().runDelayed(plugin, t -> pendingLinks.remove(code), 10, TimeUnit.MINUTES);
        return code;
    }

    private void cacheLink(UUID uuid, String discordId) {
        String prevDiscord = linkedDiscordCache.put(uuid, discordId);
        if (prevDiscord != null && !prevDiscord.equals(discordId)) linkedPlayerCache.remove(prevDiscord);
        linkedPlayerCache.put(discordId, uuid);
    }

    private void uncacheLink(UUID uuid) {
        String prev = linkedDiscordCache.remove(uuid);
        if (prev != null) linkedPlayerCache.remove(prev);
    }

    /** Drops a pending login request (player quit); a late button click then finds nothing to approve. */
    public void clearPendingLogin(UUID uuid) {
        pendingLogins.remove(uuid);
    }

    public CompletableFuture<Boolean> requestDiscordLogin(Player player) {
        if (!isEnabled()) return CompletableFuture.completedFuture(false);
        return database.findPlayer(player.getUniqueId()).thenCompose(record -> {
            if (record.isEmpty() || !record.get().hasDiscord()) return CompletableFuture.completedFuture(false);
            String discordId = record.get().discordId();
            return sendLoginRequest(player, discordId);
        });
    }

    private CompletableFuture<Boolean> sendLoginRequest(Player player, String discordId) {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        try {
            jda.retrieveUserById(discordId).queue(discordUser -> {
                discordUser.openPrivateChannel().queue(channel -> {
                    MessageEmbed embed = new EmbedBuilder().setTitle(lang.plain("discord.login-embed-title")).setDescription(lang.plain("discord.login-embed-desc", Map.of("player", player.getName()))).setColor(java.awt.Color.decode("#a855f7")).build();
                    String nonce = generateCode(16);
                    String requestIp = auth.getIp(player);
                    Button confirmBtn = Button.success("login_confirm:" + player.getUniqueId() + ":" + nonce, lang.plain("discord.login-btn-confirm"));
                    Button denyBtn = Button.danger("login_deny:" + player.getUniqueId() + ":" + nonce, lang.plain("discord.login-btn-deny"));
                    channel.sendMessageEmbeds(embed).setComponents(ActionRow.of(confirmBtn, denyBtn)).queue(msg -> {
                        PendingLogin pendingLogin = new PendingLogin(msg.getIdLong(), requestIp, nonce);
                        pendingLogins.put(player.getUniqueId(), pendingLogin);
                        lang.send(player, "discord.login-dm-sent");
                        result.complete(true);
                        plugin.getServer().getAsyncScheduler().runDelayed(plugin, t -> {
                            // Only clear this very request: a newer one (new message, new nonce) must survive.
                            if (pendingLogins.remove(player.getUniqueId(), pendingLogin)) {
                                Bukkit.getScheduler().runTask(plugin, () -> {
                                    if (player.isOnline() && !auth.isAuthenticated(player.getUniqueId())) player.kick(lang.component("discord.login-timeout"));
                                });
                                msg.delete().queue();
                            }
                        }, 2, TimeUnit.MINUTES);
                    }, error -> result.complete(false));
                }, error -> result.complete(false));
            }, error -> result.complete(false));
        } catch (Exception e) {
            plugin.getLogManager().errorKey("log.discord-dm-error",
                Map.of("message", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()), e);
            result.complete(false);
        }
        return result;
    }

    public void sendConfirmation(Player player, String action) {
        if (!isEnabled()) { lang.send(player, "discord.not-enabled"); return; }
        UUID owner = player.getUniqueId();
        database.findPlayer(owner).thenAccept(record -> {
            if (record.isEmpty() || !record.get().hasDiscord()) { lang.send(player, "discord.not-bound"); return; }
            jda.retrieveUserById(record.get().discordId()).queue(user -> user.openPrivateChannel().queue(channel -> {
                String nonce = generateCode(12);
                channel.sendMessageEmbeds(confirmEmbed(action)).setComponents(confirmRows(owner, action, nonce)).queue(msg -> {
                    registerPendingAction(msg.getIdLong(), owner, action, nonce);
                    lang.send(player, "discord.confirmation-sent-buttons");
                    msg.delete().queueAfter(CARD_LIFETIME_MINUTES, TimeUnit.MINUTES, null, err -> {});
                });
            }));
        });
    }

    /**
     * Смена пароля игроком, у которого привязан Discord: пароль меняется не сразу,
     * а после подтверждения в личных сообщениях бота. Хеш считается заранее и держится
     * в памяти до ответа — если подтверждения не будет, в базу ничего не уйдёт.
     */
    public void sendPasswordChangeConfirmation(Player player, String passwordHash) {
        if (!isEnabled()) { lang.send(player, "discord.not-enabled"); return; }
        pendingPasswordHashes.put(player.getUniqueId(), passwordHash);
        // Запрос живёт столько же, сколько само сообщение с кнопками в Discord.
        UUID uuid = player.getUniqueId();
        plugin.getServer().getAsyncScheduler().runDelayed(plugin,
            t -> pendingPasswordHashes.remove(uuid, passwordHash), 2, TimeUnit.MINUTES);
        sendConfirmation(player, "PASSWORD_CHANGE");
    }

    /**
     * Записывает отложенный хеш пароля. Возвращает false, если подтверждение
     * пришло позже срока и хеш уже убран из памяти.
     */
    private CompletableFuture<Boolean> applyPendingPasswordChange(UUID uuid) {
        String hash = pendingPasswordHashes.remove(uuid);
        if (hash == null) return CompletableFuture.completedFuture(false);
        return auth.applyPasswordHash(uuid, hash).thenApply(ok -> true);
    }

    // =========================================================================================
    // dev.lovelace.lovecore.api.discord.DiscordService — мост тикетов/репортов/апелляций LoveCore
    // =========================================================================================

    @Override
    public Optional<String> getLinkedDiscordId(UUID playerUuid) {
        String cached = linkedDiscordCache.get(playerUuid);
        if (cached == null) {
            // Не блокируем вызывающего (интерфейс синхронный) — просто прогреваем кэш на будущее.
            database.findPlayer(playerUuid).thenAccept(rec -> {
                if (rec.isPresent() && rec.get().hasDiscord()) cacheLink(playerUuid, rec.get().discordId());
            });
        }
        return Optional.ofNullable(cached);
    }

    @Override
    public Optional<UUID> getLinkedPlayer(String discordId) {
        if (discordId == null || discordId.isBlank()) return Optional.empty();
        UUID cached = linkedPlayerCache.get(discordId);
        if (cached == null) {
            database.findPlayerByDiscordId(discordId).thenAccept(rec -> rec.ifPresent(r -> cacheLink(r.uuid(), discordId)));
        }
        return Optional.ofNullable(cached);
    }

    @Override
    public String generateLinkCode(UUID playerUuid) {
        return registerPendingLinkCode(playerUuid);
    }

    @Override
    public boolean completeLink(String discordId, String code) {
        if (code == null || discordId == null || discordId.isBlank()) return false;
        UUID uuid = pendingLinks.remove(code.trim().toUpperCase(Locale.ROOT));
        if (uuid == null) return false;
        database.findPlayerByDiscordId(discordId).thenAccept(existing -> {
            if (existing.isPresent() && !existing.get().uuid().equals(uuid)) return;
            database.setDiscordId(uuid, discordId).thenRun(() -> {
                cacheLink(uuid, discordId);
                Player p = Bukkit.getPlayer(uuid);
                if (p != null) {
                    lang.send(p, "discord.bind-success");
                    auth.markAuthenticated(p, true);
                }
            });
        });
        return true;
    }

    @Override
    public void unlink(UUID playerUuid) {
        if (playerUuid == null) return;
        uncacheLink(playerUuid);
        database.setDiscordId(playerUuid, null);
    }

    @Override
    public void setLink(UUID playerUuid, String discordId) {
        if (playerUuid == null) return;
        if (discordId == null || discordId.isBlank()) { unlink(playerUuid); return; }
        cacheLink(playerUuid, discordId);
        database.setDiscordId(playerUuid, discordId);
    }

    @Override
    public CompletableFuture<Boolean> sendDirectMessage(String discordUserId, String message, DiscordEmbed embed) {
        if (!isEnabled() || discordUserId == null || discordUserId.isBlank()) {
            return CompletableFuture.completedFuture(false);
        }
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        jda.retrieveUserById(discordUserId).queue(
            user -> user.openPrivateChannel().queue(
                channel -> sendToChannel(channel, message, embed, result),
                err -> result.complete(false)),
            err -> result.complete(false));
        return result;
    }

    @Override
    public CompletableFuture<Boolean> sendChannelMessage(String channelId, String message, DiscordEmbed embed) {
        if (!isEnabled() || channelId == null || channelId.isBlank()) {
            return CompletableFuture.completedFuture(false);
        }
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        MessageChannel channel = jda.getChannelById(MessageChannel.class, channelId);
        if (channel == null) {
            result.complete(false);
            return result;
        }
        sendToChannel(channel, message, embed, result);
        return result;
    }

    private void sendToChannel(MessageChannel channel, String message, DiscordEmbed embed, CompletableFuture<Boolean> result) {
        MessageCreateBuilder builder = new MessageCreateBuilder();
        if (message != null && !message.isBlank()) builder.setContent(message);
        if (embed != null) builder.setEmbeds(toMessageEmbed(embed));
        if (builder.isEmpty()) {
            result.complete(false);
            return;
        }
        channel.sendMessage(builder.build()).queue(msg -> result.complete(true), err -> result.complete(false));
    }

    private MessageEmbed toMessageEmbed(DiscordEmbed embed) {
        EmbedBuilder eb = new EmbedBuilder();
        if (embed.title() != null) eb.setTitle(embed.title());
        if (embed.description() != null) eb.setDescription(embed.description());
        if (embed.color() != null) eb.setColor(embed.color());
        if (embed.footer() != null) eb.setFooter(embed.footer());
        if (embed.timestamp() != null) eb.setTimestamp(embed.timestamp());
        if (embed.fields() != null) {
            for (DiscordEmbed.Field f : embed.fields()) eb.addField(f.name(), f.value(), f.inline());
        }
        return eb.build();
    }

    @Override
    public CompletableFuture<String> createTicketChannel(TicketType type, String ticketId, String playerName, String initialReason) {
        String guildId = config.getDiscordGuildId();
        if (!isEnabled() || guildId.isBlank()) {
            return CompletableFuture.completedFuture(null);
        }
        Guild guild = jda.getGuildById(guildId);
        if (guild == null) {
            return CompletableFuture.completedFuture(null);
        }

        String prefix = type == TicketType.BAN_APPEAL ? "бан-" : (type == TicketType.SUPPORT ? "саппорт-" : "репорт-");
        String sanitizedName = playerName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "");
        String channelName = "ticket-" + prefix + sanitizedName;

        var action = guild.createTextChannel(channelName)
            .setTopic("Тикет: " + ticketId + " | Игрок: " + playerName + " | Причина: " + initialReason);
        String categoryId = type == TicketType.BAN_APPEAL ? config.getDiscordAppealsCategoryId() : config.getDiscordSupportCategoryId();
        if (!categoryId.isBlank()) {
            Category category = guild.getCategoryById(categoryId);
            if (category != null) action = action.setParent(category);
        }

        CompletableFuture<String> result = new CompletableFuture<>();
        action.queue(channel -> {
            activeTicketChannels.put(ticketId, channel.getId());
            ticketChannelToId.put(channel.getId(), ticketId);

            String title = "⚔ Тикет " + (type == TicketType.BAN_APPEAL ? "апелляции бана" : type == TicketType.SUPPORT ? "поддержки" : "жалобы");
            DiscordEmbed intro = new DiscordEmbed(
                title,
                "**Игрок:** `" + playerName + "`\n**Тикет ID:** `" + ticketId + "`\n**Описание:** " + initialReason
                    + "\n\n*Сообщения из этого канала синхронизируются с панелью LoveWebAdmin.*",
                0xE67E22,
                List.of(),
                "LoveAuth Ticket Bridge",
                Instant.now()
            );
            sendToChannel(channel, "@here Новое обращение от игрока `" + playerName + "`", intro, new CompletableFuture<>());
            result.complete(channel.getId());
        }, err -> result.complete(null));
        return result;
    }

    @Override
    public CompletableFuture<Boolean> closeTicketChannel(String channelId, String reason) {
        if (!isEnabled() || channelId == null || channelId.isBlank()) {
            return CompletableFuture.completedFuture(false);
        }
        TextChannel channel = jda.getTextChannelById(channelId);
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        if (channel == null) {
            result.complete(false);
            return result;
        }
        channel.sendMessage("🔒 **Тикет закрыт.** Причина: " + (reason != null ? reason : "Вопрос решён.")).queue(
            msg -> channel.delete().queueAfter(3, TimeUnit.SECONDS, v -> {
                String ticketId = ticketChannelToId.remove(channelId);
                if (ticketId != null) activeTicketChannels.remove(ticketId);
                result.complete(true);
            }, err -> result.complete(false)),
            err -> result.complete(false));
        return result;
    }

    @Override
    public void registerTicketMessageListener(TicketMessageListener listener) {
        if (listener != null) ticketMessageListeners.add(listener);
    }

    // =========================================================================================
    // Slash commands, control panel, buttons and modals
    // =========================================================================================

    private static final String CMD_MENU = "меню";
    private static final String CMD_LINK = "привязать";
    private static final String CMD_UNLINK = "отвязать";
    private static final String CMD_LOCK = "заблокировать";
    private static final String CMD_UNLOCK = "разблокировать";
    private static final String CMD_PASSWORD = "пароль";
    private static final String CMD_PASSWORD_CHANGE = "сменить";
    private static final String CMD_PASSWORD_DELETE = "удалить";
    private static final String CMD_SESSIONS = "сессии";
    private static final String CMD_INFO = "инфо";
    private static final String CMD_ONLINE = "онлайн";
    private static final String CMD_HELP = "помощь";
    private static final String OPTION_CODE = "код";

    // Component id kinds.
    private static final String KIND_PANEL = "panel";
    private static final String KIND_CONFIRM = "confirm_action";
    private static final String KIND_DENY = "deny_action";
    private static final String KIND_LOCK = "action_lock";
    private static final String KIND_MODAL = "modal";

    // Panel actions. The same names are used by the slash commands that map onto them.
    private static final String ACT_MENU = "MENU";
    private static final String ACT_REFRESH = "REFRESH";
    private static final String ACT_INFO = "INFO";
    private static final String ACT_ONLINE = "ONLINE";
    private static final String ACT_HELP = "HELP";
    private static final String ACT_LOCK = "LOCK";
    private static final String ACT_UNLOCK = "UNLOCK";
    private static final String ACT_PWD_CHANGE = "PWD_CHANGE";
    private static final String ACT_PWD_DELETE = "PWD_DELETE";
    private static final String ACT_UNLINK = "UNLINK";
    private static final String ACT_SESSIONS = "SESSIONS";
    private static final String ACT_BIND_CODE = "BIND_CODE";
    private static final String ACT_BIND_HELP = "BIND_HELP";

    private static final String MODAL_PASSWORD = "PASSWORD";
    private static final String MODAL_LINK = "LINK";

    /** Registers the Russian slash commands. They are global, so Discord may take up to an hour to show them. */
    private void registerCommands() {
        try {
            jda.updateCommands().addCommands(
                Commands.slash(CMD_MENU, lang.plain("discord.cmd-menu")),
                Commands.slash(CMD_LINK, lang.plain("discord.cmd-link"))
                    .addOption(OptionType.STRING, OPTION_CODE, lang.plain("discord.cmd-link-option"), true),
                Commands.slash(CMD_UNLINK, lang.plain("discord.cmd-unlink")),
                Commands.slash(CMD_LOCK, lang.plain("discord.cmd-lock")),
                Commands.slash(CMD_UNLOCK, lang.plain("discord.cmd-unlock")),
                Commands.slash(CMD_PASSWORD, lang.plain("discord.cmd-password")).addSubcommands(
                    new SubcommandData(CMD_PASSWORD_CHANGE, lang.plain("discord.cmd-password-change")),
                    new SubcommandData(CMD_PASSWORD_DELETE, lang.plain("discord.cmd-password-delete"))),
                Commands.slash(CMD_SESSIONS, lang.plain("discord.cmd-sessions")),
                Commands.slash(CMD_INFO, lang.plain("discord.cmd-info")),
                Commands.slash(CMD_ONLINE, lang.plain("discord.cmd-online")),
                Commands.slash(CMD_HELP, lang.plain("discord.cmd-help"))
            ).queue(ok -> {}, err -> plugin.getLogManager().warnKey("log.discord-commands-error",
                Map.of("message", String.valueOf(err.getMessage()))));
        } catch (Exception e) {
            plugin.getLogManager().warnKey("log.discord-commands-error",
                Map.of("message", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }
    }

    // --- presence ---

    private void startPresence() {
        if (!config.isDiscordPresenceEnabled()) return;
        long periodTicks = config.getDiscordPresenceIntervalSeconds() * 20L;
        presenceTask = Bukkit.getScheduler().runTaskTimer(plugin, this::updatePresence, 20L, periodTicks);
    }

    /** Runs on the main thread (reads Bukkit state) and only talks to Discord when the text changed. */
    private void updatePresence() {
        JDA current = jda;
        if (current == null) return;
        String text = DiscordFormat.presenceText(config.getDiscordPresenceFormat(),
            Bukkit.getOnlinePlayers().size(), Bukkit.getMaxPlayers());
        if (text.equals(lastPresence)) return;
        lastPresence = text;
        current.getPresence().setActivity(Activity.playing(text));
    }

    // --- threading helpers ---

    /** Runs the supplier on the Bukkit main thread and completes the future with its result. */
    private <T> CompletableFuture<T> onMain(Supplier<T> task) {
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            Bukkit.getScheduler().runTask(plugin, () -> {
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
        try {
            Bukkit.getScheduler().runTask(plugin, task);
        } catch (Exception ignored) {
            // Plugin is being disabled; nothing left to do on the main thread.
        }
    }

    private Void logAndReport(Throwable ex, InteractionHook hook) {
        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
        plugin.getLogManager().warnKey("log.listener-error", Map.of("message", String.valueOf(cause.getMessage())));
        if (hook != null) say(hook, lang.plain("discord.general-error"));
        return null;
    }

    private void say(InteractionHook hook, String text) {
        hook.sendMessage(text).setEphemeral(true).queue(null, err -> {});
    }

    /** Looks up the player linked to this Discord user; answers "not linked" itself when there is none. */
    private void withLinked(String discordId, InteractionHook hook, Consumer<DatabaseManager.PlayerRecord> action) {
        database.findPlayerByDiscordId(discordId).thenAccept(r -> {
            if (r.isEmpty()) {
                say(hook, lang.plain("discord.not-bound-dm"));
                return;
            }
            action.accept(r.get());
        }).exceptionally(ex -> logAndReport(ex, hook));
    }

    // --- component ids ---

    private String panelId(UUID owner, String action) {
        return new ActionToken(KIND_PANEL, owner, action, ActionToken.STATIC_NONCE).toId();
    }

    private String modalId(String action) {
        return new ActionToken(KIND_MODAL, null, action, ActionToken.STATIC_NONCE).toId();
    }

    // --- panel ---

    private record Panel(MessageEmbed embed, List<ActionRow> rows) {}

    private Panel buildPanel(DatabaseManager.PlayerRecord rec, int online, int max) {
        if (rec == null) {
            MessageEmbed embed = new EmbedBuilder()
                .setTitle(lang.plain("discord.panel-unlinked-title"))
                .setDescription(lang.plain("discord.panel-unlinked-desc"))
                .setColor(java.awt.Color.decode("#a855f7")).build();
            return new Panel(embed, List.of(ActionRow.of(
                Button.primary(panelId(null, ACT_BIND_CODE), lang.plain("discord.btn-bind-code")),
                Button.secondary(panelId(null, ACT_BIND_HELP), lang.plain("discord.btn-bind-help")),
                Button.secondary(panelId(null, ACT_ONLINE), lang.plain("discord.btn-online")))));
        }
        UUID id = rec.uuid();
        boolean hasPassword = rec.hasPassword() && rec.passwordEnabled();
        MessageEmbed embed = new EmbedBuilder()
            .setTitle(lang.plain("discord.panel-title", Map.of("player", rec.username())))
            .addField(lang.plain("discord.panel-field-status"), lang.plain(rec.locked() ? "discord.status-locked" : "discord.status-active"), true)
            .addField(lang.plain("discord.panel-field-password"), lang.plain(hasPassword ? "discord.password-set" : "discord.password-unset"), true)
            .addField(lang.plain("discord.panel-field-online"), online + "/" + max, true)
            .addField(lang.plain("discord.panel-field-last-login"),
                DiscordFormat.lastLogin(rec.lastLogin(), lang.plain("discord.last-login-never")), true)
            .setColor(rec.locked() ? java.awt.Color.RED : java.awt.Color.decode("#a855f7")).build();

        List<Button> second = new ArrayList<>();
        second.add(rec.locked()
            ? Button.success(panelId(id, ACT_UNLOCK), lang.plain("discord.btn-unlock"))
            : Button.danger(panelId(id, ACT_LOCK), lang.plain("discord.btn-lock")));
        second.add(Button.primary(panelId(id, ACT_PWD_CHANGE), lang.plain("discord.btn-password-change")));
        if (hasPassword) second.add(Button.danger(panelId(id, ACT_PWD_DELETE), lang.plain("discord.btn-password-delete")));

        return new Panel(embed, List.of(
            ActionRow.of(
                Button.secondary(panelId(id, ACT_INFO), lang.plain("discord.btn-info")),
                Button.secondary(panelId(id, ACT_ONLINE), lang.plain("discord.btn-online")),
                Button.secondary(panelId(id, ACT_REFRESH), lang.plain("discord.btn-refresh"))),
            ActionRow.of(second),
            ActionRow.of(
                Button.danger(panelId(id, ACT_UNLINK), lang.plain("discord.btn-unlink")),
                Button.secondary(panelId(id, ACT_SESSIONS), lang.plain("discord.btn-sessions")))));
    }

    /**
     * Sends (or, for the refresh button, edits) the control panel. {@code tokenOwner} is the account
     * the clicked button was issued for, or null for entry points that are not account-bound.
     */
    private void sendPanel(InteractionHook hook, String discordId, UUID tokenOwner, boolean edit) {
        database.findPlayerByDiscordId(discordId).thenCompose(r -> {
            DatabaseManager.PlayerRecord rec = r.orElse(null);
            if (tokenOwner != null && !OwnerCheck.isOwner(rec != null ? rec.uuid() : null, tokenOwner)) {
                say(hook, lang.plain("discord.not-owner"));
                return CompletableFuture.<Void>completedFuture(null);
            }
            return onMain(() -> new int[]{Bukkit.getOnlinePlayers().size(), Bukkit.getMaxPlayers()}).thenAccept(counts -> {
                Panel panel = buildPanel(rec, counts[0], counts[1]);
                if (edit) hook.editOriginalEmbeds(panel.embed()).setComponents(panel.rows()).queue(null, err -> {});
                else hook.sendMessageEmbeds(panel.embed()).setComponents(panel.rows()).setEphemeral(true).queue(null, err -> {});
            });
        }).exceptionally(ex -> logAndReport(ex, hook));
    }

    private void sendOnline(InteractionHook hook) {
        onMain(() -> {
            List<String> names = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) names.add(p.getName());
            return new Object[]{names, Bukkit.getMaxPlayers()};
        }).thenAccept(data -> {
            @SuppressWarnings("unchecked") List<String> names = (List<String>) data[0];
            int max = (Integer) data[1];
            StringBuilder sb = new StringBuilder(lang.plain("discord.online-header",
                Map.of("online", Integer.toString(names.size()), "max", Integer.toString(max))));
            if (names.isEmpty()) {
                sb.append("\n").append(lang.plain("discord.online-empty"));
            } else {
                int extra = names.size() - DiscordFormat.ONLINE_LIST_LIMIT;
                String more = extra > 0 ? lang.plain("discord.online-more", Map.of("count", Integer.toString(extra))) : null;
                sb.append("\n").append(DiscordFormat.onlineNames(names, DiscordFormat.ONLINE_LIST_LIMIT, more));
            }
            say(hook, DiscordFormat.truncate(sb.toString(), 2000));
        }).exceptionally(ex -> logAndReport(ex, hook));
    }

    private void sendInfo(InteractionHook hook, DatabaseManager.PlayerRecord rec) {
        CompletableFuture<List<String>> alts = rec.lastIp() == null
            ? CompletableFuture.completedFuture(List.<String>of())
            : database.getAlts(rec.lastIp());
        alts.thenAccept(list -> {
            EmbedBuilder eb = new EmbedBuilder()
                .setTitle(lang.plain("discord.info-title"))
                .addField(lang.plain("discord.info-player"), rec.username(), true)
                .addField(lang.plain("discord.info-status"), lang.plain(rec.locked() ? "discord.status-locked" : "discord.status-active"), true)
                .addField(lang.plain("discord.info-alts"), DiscordFormat.fieldValue(list, lang.plain("discord.info-none")), false)
                .setColor(rec.locked() ? java.awt.Color.RED : java.awt.Color.GREEN);
            hook.sendMessageEmbeds(eb.build()).setEphemeral(true).queue(null, err -> {});
        }).exceptionally(ex -> logAndReport(ex, hook));
    }

    private String buildHelpMessage() {
        return String.join("\n",
            lang.plain("discord.help-header"),
            lang.plain("discord.help-menu"),
            lang.plain("discord.help-link"),
            lang.plain("discord.help-unlink"),
            lang.plain("discord.help-lock"),
            lang.plain("discord.help-unlock"),
            lang.plain("discord.help-password-change"),
            lang.plain("discord.help-password-delete"),
            lang.plain("discord.help-sessions"),
            lang.plain("discord.help-info"),
            lang.plain("discord.help-online"),
            lang.plain("discord.help-help"));
    }

    // --- actions shared by slash commands and panel buttons ---

    /**
     * Routes an action that needs no modal. {@code tokenOwner} is non-null for panel buttons: the
     * clicking Discord user must then be linked to exactly that account.
     */
    private void dispatch(String action, String discordId, UUID tokenOwner, InteractionHook hook, boolean edit) {
        switch (action) {
            case ACT_ONLINE -> sendOnline(hook);
            case ACT_HELP -> say(hook, buildHelpMessage());
            case ACT_BIND_HELP -> say(hook, lang.plain("discord.bind-help"));
            case ACT_MENU, ACT_REFRESH -> sendPanel(hook, discordId, tokenOwner, edit);
            default -> withLinked(discordId, hook, rec -> {
                if (tokenOwner != null && !OwnerCheck.isOwner(rec.uuid(), tokenOwner)) {
                    say(hook, lang.plain("discord.not-owner"));
                    return;
                }
                runOwned(action, rec, hook);
            });
        }
    }

    private void runOwned(String action, DatabaseManager.PlayerRecord rec, InteractionHook hook) {
        switch (action) {
            case ACT_INFO -> sendInfo(hook, rec);
            case ACT_LOCK -> sendConfirmCard(hook, rec.uuid(), "LOCK_ACCOUNT");
            case ACT_UNLOCK -> database.setLocked(rec.uuid(), false)
                .thenRun(() -> say(hook, lang.plain("discord.unlocked-dm")))
                .exceptionally(ex -> logAndReport(ex, hook));
            case ACT_PWD_DELETE -> {
                if (!(rec.hasPassword() && rec.passwordEnabled())) say(hook, lang.plain("discord.no-password-to-delete"));
                else sendConfirmCard(hook, rec.uuid(), "REMOVE_PASSWORD");
            }
            case ACT_UNLINK -> {
                // Same guard as the in-game menu: without a password the account would become unreachable.
                if (!(rec.hasPassword() && rec.passwordEnabled())) say(hook, lang.plain("discord.unlink-requires-password"));
                else sendConfirmCard(hook, rec.uuid(), "UNLINK");
            }
            case ACT_SESSIONS -> auth.getSessionManager().invalidate(rec.uuid())
                .thenRun(() -> say(hook, lang.plain("discord.sessions-done")))
                .exceptionally(ex -> logAndReport(ex, hook));
            default -> say(hook, lang.plain("discord.login-expired"));
        }
    }

    // --- confirmation cards ---

    private static final long CARD_LIFETIME_MINUTES = 2;

    private MessageEmbed confirmEmbed(String action) {
        return new EmbedBuilder().setTitle(lang.plain("discord.confirm-action-title"))
            .setDescription(lang.plain("discord.action-" + action.toLowerCase(Locale.ROOT)))
            .setColor(java.awt.Color.decode("#a855f7"))
            .setFooter(lang.plain("discord.confirm-action-footer")).build();
    }

    private List<ActionRow> confirmRows(UUID owner, String action, String nonce) {
        Button confirm = Button.success(new ActionToken(KIND_CONFIRM, owner, action, nonce).toId(), lang.plain("discord.action-btn-confirm"));
        Button deny = Button.danger(new ActionToken(KIND_DENY, owner, action, nonce).toId(), lang.plain("discord.action-btn-deny"));
        Button lockBtn = Button.danger(new ActionToken(KIND_LOCK, owner, action, nonce).toId(), lang.plain("discord.lock-btn-alert"));
        return List.of(ActionRow.of(confirm, deny), ActionRow.of(lockBtn));
    }

    /** Remembers the card and forgets it after its lifetime, so a late click finds nothing to confirm. */
    private PendingAction registerPendingAction(long messageId, UUID owner, String action, String nonce) {
        PendingAction pending = new PendingAction(messageId, owner, action, nonce);
        pendingActions.put(nonce, pending);
        plugin.getServer().getAsyncScheduler().runDelayed(plugin,
            t -> pendingActions.remove(nonce, pending), CARD_LIFETIME_MINUTES, TimeUnit.MINUTES);
        return pending;
    }

    private void sendConfirmCard(InteractionHook hook, UUID owner, String action) {
        String nonce = generateCode(12);
        hook.sendMessageEmbeds(confirmEmbed(action)).setComponents(confirmRows(owner, action, nonce)).setEphemeral(true)
            .queue(msg -> registerPendingAction(msg.getIdLong(), owner, action, nonce), err -> {});
    }

    private void finishCard(InteractionHook hook, String text) {
        hook.editOriginal(text).setEmbeds(Collections.emptyList()).setComponents(Collections.emptyList()).queue(null, err -> {});
    }

    private void handleCardButton(ButtonInteractionEvent e, ActionToken token) {
        PendingAction pending = pendingActions.get(token.nonce());
        if (pending == null || !pending.matches(e.getMessageIdLong(), token)) {
            e.reply(lang.plain("discord.login-expired")).setEphemeral(true).queue();
            return;
        }
        e.deferEdit().queue();
        InteractionHook hook = e.getHook();
        database.findPlayerByDiscordId(e.getUser().getId()).thenAccept(r -> {
            DatabaseManager.PlayerRecord rec = r.orElse(null);
            // A stranger's click must not burn the owner's card: verify first, consume second.
            if (!OwnerCheck.isOwner(rec != null ? rec.uuid() : null, token.owner())) {
                say(hook, lang.plain("discord.not-owner"));
                return;
            }
            if (!pendingActions.remove(token.nonce(), pending)) {
                say(hook, lang.plain("discord.login-expired"));
                return;
            }
            switch (token.kind()) {
                case KIND_CONFIRM -> runConfirmed(rec, token.action())
                    .thenAccept(text -> finishCard(hook, text)).exceptionally(ex -> logAndReport(ex, hook));
                case KIND_LOCK -> {
                    // "It was not me": drop any password change that was waiting on this card as well.
                    pendingPasswordHashes.remove(token.owner());
                    runConfirmed(rec, "LOCK_ACCOUNT")
                        .thenAccept(text -> finishCard(hook, text)).exceptionally(ex -> logAndReport(ex, hook));
                }
                default -> {
                    if ("PASSWORD_CHANGE".equals(token.action())) pendingPasswordHashes.remove(token.owner());
                    finishCard(hook, lang.plain("discord.action-cancelled"));
                }
            }
        }).exceptionally(ex -> logAndReport(ex, hook));
    }

    /** Applies a confirmed action and resolves with the text to show on the card. */
    private CompletableFuture<String> runConfirmed(DatabaseManager.PlayerRecord rec, String action) {
        UUID uuid = rec.uuid();
        String name = rec.username();
        switch (action) {
            case "UNLINK":
                return database.setDiscordId(uuid, null).thenApply(v -> {
                    uncacheLink(uuid);
                    syncWithLoveCore(uuid, null);
                    plugin.getLogManager().database(uuid, "DISCORD_UNLINK", name, "discord");
                    notifyPlayer(uuid, "discord.unlinked");
                    return lang.plain("discord.unlinked-dm");
                });
            case "REMOVE_PASSWORD":
                return database.setPasswordEnabled(uuid, false).thenApply(v -> {
                    plugin.getLogManager().database(uuid, "PASSWORD_DELETE", name, "discord");
                    notifyPlayer(uuid, "commands.password-removed");
                    return lang.plain("discord.password-deleted-dm");
                });
            case "PASSWORD_CHANGE":
                return applyPendingPasswordChange(uuid)
                    .thenApply(applied -> lang.plain(applied ? "discord.password-changed-dm" : "discord.login-expired"));
            case "LOCK_ACCOUNT":
                return database.setLocked(uuid, true).thenApply(v -> {
                    plugin.getLogManager().database(uuid, "MANUAL_LOCK", name, "discord");
                    runMain(() -> {
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

    /** Sends an in-game message to the player if they are online (main thread). */
    private void notifyPlayer(UUID uuid, String key) {
        runMain(() -> {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) lang.send(p, key);
        });
    }

    // --- linking ---

    /** Consumes a link code for this Discord user and answers on the hook. */
    private void linkByCode(String discordId, String rawCode, InteractionHook hook) {
        String code = rawCode == null ? "" : rawCode.trim().toUpperCase(Locale.ROOT);
        if (code.isEmpty()) {
            say(hook, lang.plain("discord.link-code-empty"));
            return;
        }
        UUID uuid = pendingLinks.remove(code);
        if (uuid == null) {
            say(hook, lang.plain("discord.link-invalid-code"));
            return;
        }
        database.findPlayerByDiscordId(discordId).thenCompose(existing -> {
            if (existing.isPresent() && !existing.get().uuid().equals(uuid)) {
                say(hook, lang.plain("discord.already-bound-other"));
                return CompletableFuture.<Void>completedFuture(null);
            }
            return database.setDiscordId(uuid, discordId).thenRun(() -> {
                cacheLink(uuid, discordId);
                syncWithLoveCore(uuid, discordId);
                say(hook, lang.plain("discord.link-success-dm"));
                runMain(() -> {
                    Player p = Bukkit.getPlayer(uuid);
                    if (p == null) return;
                    lang.send(p, "discord.bind-success");
                    plugin.getLogManager().database(uuid, "DISCORD_LINK", p.getName(), auth.getIp(p));
                    auth.markAuthenticated(p, true);
                    lang.notifyEvent(p, "actionbar.discord-bound", Map.of());
                });
            });
        }).exceptionally(ex -> logAndReport(ex, hook));
    }

    // --- modals ---

    private Modal passwordModal() {
        TextInput password = TextInput.create("password", lang.plain("discord.modal-password-field"), TextInputStyle.SHORT)
            .setRequired(true).setMaxLength(PasswordInput.MAX_LENGTH).build();
        TextInput repeat = TextInput.create("repeat", lang.plain("discord.modal-password-repeat"), TextInputStyle.SHORT)
            .setRequired(true).setMaxLength(PasswordInput.MAX_LENGTH).build();
        return Modal.create(modalId(MODAL_PASSWORD), lang.plain("discord.modal-password-title"))
            .addComponents(ActionRow.of(password), ActionRow.of(repeat)).build();
    }

    private Modal linkModal() {
        TextInput code = TextInput.create("code", lang.plain("discord.modal-link-field"), TextInputStyle.SHORT)
            .setRequired(true).setMaxLength(32).build();
        return Modal.create(modalId(MODAL_LINK), lang.plain("discord.modal-link-title"))
            .addComponents(ActionRow.of(code)).build();
    }

    private String modalValue(ModalInteractionEvent e, String id) {
        ModalMapping mapping = e.getValue(id);
        return mapping == null ? "" : mapping.getAsString();
    }

    private void handlePasswordModal(ModalInteractionEvent e, InteractionHook hook) {
        String password = modalValue(e, "password");
        String repeat = modalValue(e, "repeat");
        int minLength = Math.max(3, config.getMinPasswordLength());
        switch (PasswordInput.validate(password, repeat, minLength, PasswordInput.MAX_LENGTH)) {
            case LENGTH -> {
                say(hook, lang.plain("register.password-length",
                    Map.of("min", Integer.toString(minLength), "max", Integer.toString(PasswordInput.MAX_LENGTH))));
                return;
            }
            case CHARSET_INVALID -> {
                say(hook, lang.plain("register.password-invalid-chars"));
                return;
            }
            case MISMATCH -> {
                say(hook, lang.plain("discord.password-mismatch"));
                return;
            }
            case OK -> { }
        }
        // The account comes from the clicking Discord user, so the modal cannot be aimed at someone else.
        withLinked(e.getUser().getId(), hook, rec ->
            // applyPasswordHash also drops the stored sessions: a changed password must not leave old ones alive.
            auth.hashPasswordAsync(password)
                .thenCompose(hash -> auth.applyPasswordHash(rec.uuid(), hash))
                .thenRun(() -> say(hook, lang.plain("discord.password-changed-dm")))
                .exceptionally(ex -> logAndReport(ex, hook)));
    }

    private class DiscordEventListener extends ListenerAdapter {
        @Override
        public void onMessageReceived(MessageReceivedEvent e) {
            if (e.getAuthor().isBot()) return;
            if (!e.isFromType(ChannelType.PRIVATE)) {
                String ticketId = ticketChannelToId.get(e.getChannel().getId());
                if (ticketId != null) {
                    String content = e.getMessage().getContentRaw();
                    if (!content.isBlank()) {
                        for (TicketMessageListener listener : ticketMessageListeners) {
                            try {
                                listener.onTicketMessage(ticketId, e.getAuthor().getName(), true, content);
                            } catch (Throwable t) {
                                plugin.getLogger().warning("Ошибка ticket listener: " + t.getMessage());
                            }
                        }
                    }
                }
                return;
            }
            // Text commands in DMs are gone: point the user at the slash commands instead.
            e.getChannel().sendMessage(lang.plain("discord.dm-hint"))
                .setComponents(ActionRow.of(Button.primary(panelId(null, ACT_MENU), lang.plain("discord.dm-hint-btn"))))
                .queue(null, err -> {});
        }

        @Override
        public void onSlashCommandInteraction(SlashCommandInteractionEvent e) {
            String name = e.getName();
            String sub = e.getSubcommandName();
            // A modal has to be the very first response to the interaction, so it cannot wait for the database.
            if (CMD_PASSWORD.equals(name) && CMD_PASSWORD_CHANGE.equals(sub)) {
                e.replyModal(passwordModal()).queue();
                return;
            }
            e.deferReply(true).queue();
            InteractionHook hook = e.getHook();
            String discordId = e.getUser().getId();
            switch (name) {
                case CMD_MENU -> dispatch(ACT_MENU, discordId, null, hook, false);
                case CMD_LINK -> {
                    OptionMapping code = e.getOption(OPTION_CODE);
                    linkByCode(discordId, code == null ? null : code.getAsString(), hook);
                }
                case CMD_UNLINK -> dispatch(ACT_UNLINK, discordId, null, hook, false);
                case CMD_LOCK -> dispatch(ACT_LOCK, discordId, null, hook, false);
                case CMD_UNLOCK -> dispatch(ACT_UNLOCK, discordId, null, hook, false);
                case CMD_PASSWORD -> {
                    if (CMD_PASSWORD_DELETE.equals(sub)) dispatch(ACT_PWD_DELETE, discordId, null, hook, false);
                    else say(hook, lang.plain("discord.login-expired"));
                }
                case CMD_SESSIONS -> dispatch(ACT_SESSIONS, discordId, null, hook, false);
                case CMD_INFO -> dispatch(ACT_INFO, discordId, null, hook, false);
                case CMD_ONLINE -> dispatch(ACT_ONLINE, discordId, null, hook, false);
                case CMD_HELP -> dispatch(ACT_HELP, discordId, null, hook, false);
                default -> say(hook, lang.plain("discord.login-expired"));
            }
        }

        @Override
        public void onModalInteraction(ModalInteractionEvent e) {
            ActionToken token = ActionToken.parse(e.getModalId()).orElse(null);
            if (token == null || !KIND_MODAL.equals(token.kind())) return;
            e.deferReply(true).queue();
            InteractionHook hook = e.getHook();
            switch (token.action()) {
                case MODAL_PASSWORD -> handlePasswordModal(e, hook);
                case MODAL_LINK -> linkByCode(e.getUser().getId(), modalValue(e, "code"), hook);
                default -> say(hook, lang.plain("discord.login-expired"));
            }
        }

        @Override
        public void onButtonInteraction(ButtonInteractionEvent e) {
            String id = e.getComponentId();
            if (id.startsWith("login_confirm:")) {
                String[] parts = id.split(":");
                UUID u;
                try { u = UUID.fromString(parts[1]); } catch (IllegalArgumentException | ArrayIndexOutOfBoundsException ex) { return; }
                String nonce = parts.length > 2 ? parts[2] : null;
                PendingLogin pending = pendingLogins.get(u);
                // Message id and nonce must match the request we issued; the entry is consumed atomically.
                if (pending == null || !pending.matchesRequest(e.getMessageIdLong(), nonce) || !pendingLogins.remove(u, pending)) {
                    e.reply(lang.plain("discord.login-expired")).setEphemeral(true).queue();
                    return;
                }
                e.getMessage().delete().queue();
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Player p = Bukkit.getPlayer(u);
                    if (p == null) return;
                    // The player may have reconnected from another address since the request was sent.
                    if (!pending.matchesIp(auth.getIp(p))) {
                        p.kick(lang.component("discord.login-denied-kick"));
                        return;
                    }
                    auth.markAuthenticated(p, true);
                    lang.send(p, "discord.login-approved");
                });
                e.reply(lang.plain("discord.login-confirmed-dm")).setEphemeral(true).queue();
                return;
            }
            if (id.startsWith("login_deny:")) {
                String[] parts = id.split(":");
                UUID u;
                try { u = UUID.fromString(parts[1]); } catch (IllegalArgumentException | ArrayIndexOutOfBoundsException ex) { return; }
                String nonce = parts.length > 2 ? parts[2] : null;
                PendingLogin pending = pendingLogins.get(u);
                if (pending == null || !pending.matchesRequest(e.getMessageIdLong(), nonce) || !pendingLogins.remove(u, pending)) {
                    e.reply(lang.plain("discord.login-expired")).setEphemeral(true).queue();
                    return;
                }
                e.getMessage().delete().queue();
                Bukkit.getScheduler().runTask(plugin, () -> { Player p = Bukkit.getPlayer(u); if (p != null) p.kick(lang.component("discord.login-denied-kick")); });
                e.reply(lang.plain("discord.login-denied-dm")).setEphemeral(true).queue();
                return;
            }

            ActionToken token = ActionToken.parse(id).orElse(null);
            if (token == null) {
                e.reply(lang.plain("discord.login-expired")).setEphemeral(true).queue();
                return;
            }
            switch (token.kind()) {
                case KIND_CONFIRM, KIND_DENY, KIND_LOCK -> handleCardButton(e, token);
                case KIND_PANEL -> handlePanelButton(e, token);
                default -> e.reply(lang.plain("discord.login-expired")).setEphemeral(true).queue();
            }
        }

        private void handlePanelButton(ButtonInteractionEvent e, ActionToken token) {
            // Modals must be the first response, so ownership is resolved on submit from the clicking user.
            if (ACT_PWD_CHANGE.equals(token.action())) { e.replyModal(passwordModal()).queue(); return; }
            if (ACT_BIND_CODE.equals(token.action())) { e.replyModal(linkModal()).queue(); return; }
            boolean refresh = ACT_REFRESH.equals(token.action());
            if (refresh) e.deferEdit().queue();
            else e.deferReply(true).queue();
            dispatch(token.action(), e.getUser().getId(), token.owner(), e.getHook(), refresh);
        }
    }

    private String generateCode(int l) { String c = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; StringBuilder s = new StringBuilder(); for (int i = 0; i < l; i++) s.append(c.charAt(random.nextInt(c.length()))); return s.toString(); }
    private String generateNumericCode(int l) { String c = "ABCDEFGHJKLMNPQRSTUVWXYZ0123456789"; StringBuilder s = new StringBuilder(); for (int i = 0; i < l; i++) s.append(c.charAt(random.nextInt(c.length()))); return s.toString(); }

    public void handleBotLinkCommand(String c, String d) {
        UUID u = pendingLinks.remove(c);
        if (u != null) database.findPlayerByDiscordId(d).thenAccept(ex -> {
            if (ex.isPresent()) return;
            database.setDiscordId(u, d).thenRun(() -> {
                cacheLink(u, d);
                syncWithLoveCore(u, d);
                Player p = Bukkit.getPlayer(u);
                if (p != null) {
                    lang.send(p, "discord.bind-success");
                    plugin.getLogManager().database(u, "DISCORD_LINK", p.getName(), auth.getIp(p));
                    auth.markAuthenticated(p, true);
                }
            });
        });
    }

    public void handleBotUnlockCommand(String d) { database.findPlayerByDiscordId(d).thenAccept(r -> { if (r.isPresent()) database.setLocked(r.get().uuid(), false); }); }

    public void syncWithLoveCore(UUID uuid, String discordId) {
        if (uuid == null) return;
        try {
            if (Bukkit.getPluginManager().isPluginEnabled("LoveCore")) {
                dev.lovelace.lovecore.api.LoveCore.service(dev.lovelace.lovecore.api.discord.DiscordService.class)
                    .ifPresent(core -> {
                        if (discordId != null && !discordId.isBlank()) {
                            core.setLink(uuid, discordId);
                        } else {
                            core.unlink(uuid);
                        }
                    });
            }
        } catch (Throwable ignored) {}
    }
}
