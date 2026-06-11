package me.lovelace.loveAuth.discord;

import me.lovelace.loveAuth.LoveAuth;
import me.lovelace.loveAuth.auth.AuthManager;
import me.lovelace.loveAuth.config.ConfigManager;
import me.lovelace.loveAuth.database.DatabaseManager;
import me.lovelace.loveAuth.lang.LangManager;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.requests.GatewayIntent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.security.SecureRandom;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public final class DiscordAuthManager {
    private JDA jda;
    private final LoveAuth plugin;
    private final ConfigManager config;
    private final LangManager lang;
    private final DatabaseManager database;
    private final AuthManager auth;

    private final Map<String, UUID> pendingLinks = new ConcurrentHashMap<>();
    private final Map<UUID, Long> pendingLogins = new ConcurrentHashMap<>();
    private final Map<UUID, PendingConfirmation> pendingConfirmations = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

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
        } catch (Exception e) {
            plugin.getLogManager().errorKey("log.discord-start-error",
                Map.of("message", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()), e);
            jda = null;
        }
    }

    public boolean isEnabled() { return jda != null; }

    public void shutdown() {
        if (jda != null) { jda.shutdown(); jda = null; }
    }

    public void startBinding(Player player) {
        if (!isEnabled()) { lang.send(player, "discord.not-enabled"); return; }

        String code = generateCode(8);
        pendingLinks.put(code, player.getUniqueId());

        plugin.getServer().getAsyncScheduler().runDelayed(plugin,
            t -> pendingLinks.remove(code), 10, TimeUnit.MINUTES);

        net.kyori.adventure.text.Component msg = net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
            .deserialize(lang.get("discord.bind-instructions", Map.of("code", code)));
        player.sendMessage(msg);
    }

    public CompletableFuture<Boolean> requestDiscordLogin(Player player) {
        if (!isEnabled()) return CompletableFuture.completedFuture(false);

        return database.findPlayer(player.getUniqueId()).thenCompose(record -> {
            if (record.isEmpty() || !record.get().hasDiscord()) {
                return CompletableFuture.completedFuture(false);
            }
            String discordId = record.get().discordId();
            return sendLoginRequest(player, discordId);
        });
    }

    private CompletableFuture<Boolean> sendLoginRequest(Player player, String discordId) {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        try {
            jda.retrieveUserById(discordId).queue(discordUser -> {
                discordUser.openPrivateChannel().queue(channel -> {
                    MessageEmbed embed = new EmbedBuilder()
                        .setTitle(lang.plain("discord.login-embed-title"))
                        .setDescription(lang.plain("discord.login-embed-desc",
                            Map.of("player", player.getName())))
                        .setColor(java.awt.Color.decode("#a855f7"))
                        .build();

                    Button confirmBtn = Button.success(
                        "login_confirm:" + player.getUniqueId(),
                        Emoji.fromUnicode("✅") + " " + lang.plain("discord.login-btn-confirm"));
                    Button denyBtn = Button.danger(
                        "login_deny:" + player.getUniqueId(),
                        Emoji.fromUnicode("❌") + " " + lang.plain("discord.login-btn-deny"));

                    channel.sendMessageEmbeds(embed)
                        .setComponents(ActionRow.of(confirmBtn, denyBtn))
                        .queue(msg -> {
                            pendingLogins.put(player.getUniqueId(), msg.getIdLong());
                            lang.send(player, "discord.login-dm-sent");
                            result.complete(true);

                            plugin.getServer().getAsyncScheduler().runDelayed(plugin, t -> {
                                if (pendingLogins.remove(player.getUniqueId()) != null) {
                                    Bukkit.getScheduler().runTask(plugin, () -> {
                                        if (player.isOnline() && !auth.isAuthenticated(player.getUniqueId())) {
                                            player.kick(lang.component("discord.login-timeout"));
                                        }
                                    });
                                    msg.editMessageComponents(
                                        ActionRow.of(confirmBtn.asDisabled(), denyBtn.asDisabled())
                                    ).queue();
                                }
                            }, 2, TimeUnit.MINUTES);
                        }, error -> result.complete(false));
                }, error -> result.complete(false));
            }, error -> result.complete(false));
        } catch (Exception e) {
            plugin.getLogManager().errorKey("log.discord-dm-error",
                Map.of("message", e.getMessage() != null ? e.getMessage() : ""), e);
            result.complete(false);
        }
        return result;
    }

    public void sendConfirmationCode(Player player, String action) {
        if (!isEnabled()) { lang.send(player, "discord.not-enabled"); return; }

        database.findPlayer(player.getUniqueId()).thenAccept(record -> {
            if (record.isEmpty() || !record.get().hasDiscord()) {
                lang.send(player, "discord.not-bound");
                return;
            }
            String discordId = record.get().discordId();
            String code = generateNumericCode(6);
            pendingConfirmations.put(player.getUniqueId(), new PendingConfirmation(code, action));

            plugin.getServer().getAsyncScheduler().runDelayed(plugin,
                t -> pendingConfirmations.remove(player.getUniqueId()), 5, TimeUnit.MINUTES);

            try {
                jda.retrieveUserById(discordId).queue(discordUser -> {
                    discordUser.openPrivateChannel().queue(channel -> {
                        String msg = lang.get("discord.confirmation-code-msg",
                            Map.of("code", code, "action", lang.get("discord.action-" + action.toLowerCase())));
                        channel.sendMessage(msg).queue();
                        lang.send(player, "discord.confirmation-sent");

                        plugin.getChatInputHandler().awaitInput(player,
                            "discord.prompt-confirmation", input -> {
                            PendingConfirmation pending = pendingConfirmations.remove(player.getUniqueId());
                            if (pending != null && pending.code().equals(input.toUpperCase())) {
                                handleConfirmedAction(player, action);
                            } else {
                                lang.send(player, "discord.wrong-code");
                            }
                        });
                    });
                });
            } catch (Exception e) {
                plugin.getLogManager().errorKey("log.discord-dm-error",
                    Map.of("message", e.getMessage() != null ? e.getMessage() : ""), e);
            }
        });
    }

    private void handleConfirmedAction(Player player, String action) {
        switch (action) {
            case "UNLINK" -> database.setDiscordId(player.getUniqueId(), null)
                .thenRun(() -> lang.send(player, "discord.unlinked"));
            case "REMOVE_PASSWORD" -> database.setPasswordEnabled(player.getUniqueId(), false)
                .thenRun(() -> lang.send(player, "commands.password-removed"));
            case "LOCK_ACCOUNT" -> database.setLocked(player.getUniqueId(), true)
                .thenRun(() -> {
                    lang.send(player, "discord.account-locked-self");
                    Bukkit.getScheduler().runTask(plugin, () ->
                        player.kick(lang.component("block.account-locked")));
                });
        }
    }

    private class DiscordEventListener extends ListenerAdapter {
        @Override
        public void onMessageReceived(MessageReceivedEvent event) {
            if (event.getAuthor().isBot()) return;
            if (!event.isFromType(ChannelType.PRIVATE)) return;

            String content = event.getMessage().getContentRaw().trim();
            String discordId = event.getAuthor().getId();

            if (content.toLowerCase().startsWith("/link ")) {
                String code = content.substring(6).trim().toUpperCase();
                UUID uuid = pendingLinks.remove(code);
                if (uuid == null) {
                    event.getChannel().sendMessage(lang.plain("discord.link-invalid-code")).queue();
                    return;
                }
                database.setDiscordId(uuid, discordId).thenRun(() -> {
                    event.getChannel().sendMessage(lang.plain("discord.link-success-dm")).queue();
                    Player player = Bukkit.getPlayer(uuid);
                    if (player != null) lang.send(player, "discord.bind-success");
                });
                return;
            }

            if (content.equalsIgnoreCase("/help")) {
                event.getChannel().sendMessage(buildHelpMessage()).queue();
            }
        }

        @Override
        public void onButtonInteraction(ButtonInteractionEvent event) {
            String componentId = event.getComponentId();

            if (componentId.startsWith("login_confirm:")) {
                UUID uuid = UUID.fromString(componentId.substring("login_confirm:".length()));
                if (pendingLogins.remove(uuid) == null) {
                    event.reply(lang.plain("discord.login-expired")).setEphemeral(true).queue();
                    return;
                }
                event.editComponents(ActionRow.of(
                    event.getMessage().getButtons().stream()
                        .map(Button::asDisabled).toList()
                )).queue();

                Bukkit.getScheduler().runTask(plugin, () -> {
                    Player player = Bukkit.getPlayer(uuid);
                    if (player != null && player.isOnline()) {
                        auth.markAuthenticated(player, true);
                        lang.send(player, "discord.login-approved");
                    }
                });
                event.getHook().sendMessage(lang.plain("discord.login-confirmed-dm")).queue();
            }

            if (componentId.startsWith("login_deny:")) {
                UUID uuid = UUID.fromString(componentId.substring("login_deny:".length()));
                pendingLogins.remove(uuid);

                event.editComponents(ActionRow.of(
                    event.getMessage().getButtons().stream()
                        .map(Button::asDisabled).toList()
                )).queue();

                Bukkit.getScheduler().runTask(plugin, () -> {
                    Player player = Bukkit.getPlayer(uuid);
                    if (player != null && player.isOnline()) {
                        player.kick(lang.component("discord.login-denied-kick"));
                    }
                });
                event.getHook().sendMessage(lang.plain("discord.login-denied-dm")).queue();
            }
        }
    }

    private String buildHelpMessage() {
        return lang.plain("discord.help-header") + "\n" +
               lang.plain("discord.help-link") + "\n" +
               lang.plain("discord.help-help");
    }

    private String generateCode(int length) {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) sb.append(chars.charAt(random.nextInt(chars.length())));
        return sb.toString();
    }

    private String generateNumericCode(int length) {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZ0123456789";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) sb.append(chars.charAt(random.nextInt(chars.length())));
        return sb.toString();
    }

    private record PendingConfirmation(String code, String action) {}

    public void handleBotLinkCommand(String code, String discordId) {
        UUID uuid = pendingLinks.remove(code);
        if (uuid != null) {
            database.setDiscordId(uuid, discordId).thenRun(() -> {
                Player player = plugin.getServer().getPlayer(uuid);
                if (player != null) {
                    lang.send(player, "discord.bind-success");
                    auth.markAuthenticated(player, true);
                }
            });
        }
    }

    public void handleBotUnlockCommand(String discordId) {
        database.findPlayerByDiscordId(discordId).thenAccept(record -> {
            if (record.isPresent()) {
                database.setLocked(record.get().uuid(), false).thenRun(() -> {
                    plugin.getLogger().info("[Discord] Account " + record.get().username() + " unlocked via Discord by " + discordId);
                });
            }
        });
    }
}
