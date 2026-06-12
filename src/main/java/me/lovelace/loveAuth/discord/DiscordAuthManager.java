package me.lovelace.loveAuth.discord;

import me.lovelace.loveAuth.LoveAuth;
import me.lovelace.loveAuth.auth.AuthManager;
import me.lovelace.loveAuth.config.ConfigManager;
import me.lovelace.loveAuth.database.DatabaseManager;
import me.lovelace.loveAuth.lang.LangManager;
import me.lovelace.loveAuth.security.SecurityUtils;
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
import java.util.List;
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
                        lang.plain("discord.login-btn-confirm"));
                    Button denyBtn = Button.danger(
                        "login_deny:" + player.getUniqueId(),
                        lang.plain("discord.login-btn-deny"));

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
                                    msg.delete().queue();
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
                t -> pendingConfirmations.remove(player.getUniqueId()), 1, TimeUnit.MINUTES);

            try {
                jda.retrieveUserById(discordId).queue(discordUser -> {
                    discordUser.openPrivateChannel().queue(channel -> {
                        String msgContent = lang.get("discord.confirmation-code-msg",
                            Map.of("code", code, "action", lang.get("discord.action-" + action.toLowerCase())));
                        
                        Button lockBtn = Button.danger(
                            "action_lock:" + player.getUniqueId(),
                            lang.plain("discord.lock-btn-alert"));

                        channel.sendMessage(msgContent)
                            .setComponents(ActionRow.of(lockBtn))
                            .queue(msg -> {
                                lang.send(player, "discord.confirmation-sent");
                                msg.delete().queueAfter(1, TimeUnit.MINUTES, null, err -> {});

                                plugin.getChatInputHandler().awaitInput(player,
                                    "discord.prompt-confirmation", input -> {
                                    PendingConfirmation pending = pendingConfirmations.remove(player.getUniqueId());
                                    if (pending != null && pending.code().equals(input.toUpperCase())) {
                                        handleConfirmedAction(player, action);
                                        msg.delete().queue(null, err -> {});
                                    } else {
                                        lang.send(player, "discord.wrong-code");
                                    }
                                });
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
                .thenRun(() -> {
                    lang.send(player, "discord.unlinked");
                    plugin.getLogManager().database(player.getUniqueId(), "DISCORD_UNLINK", player.getName(), player.getAddress().getAddress().getHostAddress());
                });
            case "REMOVE_PASSWORD" -> database.setPasswordEnabled(player.getUniqueId(), false)
                .thenRun(() -> {
                    lang.send(player, "commands.password-removed");
                    plugin.getLogManager().database(player.getUniqueId(), "PASSWORD_DELETE", player.getName(), player.getAddress().getAddress().getHostAddress());
                });
            case "LOCK_ACCOUNT" -> database.setLocked(player.getUniqueId(), true)
                .thenRun(() -> {
                    lang.send(player, "discord.account-locked-self");
                    plugin.getLogManager().database(player.getUniqueId(), "MANUAL_LOCK", player.getName(), player.getAddress().getAddress().getHostAddress());
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
            String[] args = content.split("\\s+");
            String discordId = event.getAuthor().getId();
            boolean isAdmin = config.getDiscordAdminIds().contains(discordId);

            String cmd = args[0].toLowerCase();
            if (cmd.equals("/link") || cmd.equals("/привязать")) {
                if (args.length < 2) { event.getChannel().sendMessage(lang.plain("discord.link-usage")).queue(); return; }
                String code = args[1].toUpperCase();
                UUID uuid = pendingLinks.remove(code);
                if (uuid == null) {
                    event.getChannel().sendMessage(lang.plain("discord.link-invalid-code")).queue();
                    return;
                }
                database.setDiscordId(uuid, discordId).thenRun(() -> {
                    event.getChannel().sendMessage(lang.plain("discord.link-success-dm")).queue();
                    Player player = Bukkit.getPlayer(uuid);
                    if (player != null) {
                        lang.send(player, "discord.bind-success");
                        plugin.getLogManager().database(uuid, "DISCORD_LINK", player.getName(), player.getAddress().getAddress().getHostAddress());
                        auth.markAuthenticated(player, true);
                    } else {
                        plugin.getLogManager().database(uuid, "DISCORD_LINK", "Unknown", null);
                    }
                });
            } else if (cmd.equals("/unlink") || cmd.equals("/отвязать")) {
                database.findPlayerByDiscordId(discordId).thenAccept(record -> {
                    if (record.isEmpty()) { event.getChannel().sendMessage(lang.plain("discord.not-bound-dm")).queue(); return; }
                    database.setDiscordId(record.get().uuid(), null).thenRun(() -> {
                        event.getChannel().sendMessage(lang.plain("discord.unlinked-dm")).queue();
                        plugin.getLogManager().database(record.get().uuid(), "DISCORD_UNLINK_DM", record.get().username(), null);
                    });
                });
            } else if (cmd.equals("/lock") || cmd.equals("/заблокировать")) {
                database.findPlayerByDiscordId(discordId).thenAccept(record -> {
                    if (record.isEmpty()) { event.getChannel().sendMessage(lang.plain("discord.not-bound-dm")).queue(); return; }
                    database.setLocked(record.get().uuid(), true).thenRun(() -> {
                        event.getChannel().sendMessage(lang.plain("discord.locked-dm")).queue();
                        plugin.getLogManager().database(record.get().uuid(), "DISCORD_LOCK_DM", record.get().username(), null);
                        Player p = Bukkit.getPlayer(record.get().uuid());
                        if (p != null) Bukkit.getScheduler().runTask(plugin, () -> p.kick(lang.component("block.account-locked")));
                    });
                });
            } else if (cmd.equals("/unlock") || cmd.equals("/разблокировать")) {
                database.findPlayerByDiscordId(discordId).thenAccept(record -> {
                    if (record.isEmpty()) { event.getChannel().sendMessage(lang.plain("discord.not-bound-dm")).queue(); return; }
                    database.setLocked(record.get().uuid(), false).thenRun(() -> {
                        event.getChannel().sendMessage(lang.plain("discord.unlocked-dm")).queue();
                        plugin.getLogManager().database(record.get().uuid(), "DISCORD_UNLOCK_DM", record.get().username(), null);
                    });
                });
            } else if (cmd.equals("/password") || cmd.equals("/пароль")) {
                if (args.length < 2) { event.getChannel().sendMessage("Usage: /password <change|delete|set> [new_pass]").queue(); return; }
                database.findPlayerByDiscordId(discordId).thenAccept(record -> {
                    if (record.isEmpty()) { event.getChannel().sendMessage(lang.plain("discord.not-bound-dm")).queue(); return; }
                    UUID uuid = record.get().uuid();
                    String sub = args[1].toLowerCase();
                    if (sub.equals("change") || sub.equals("изменить") || sub.equals("set") || sub.equals("установить")) {
                        if (args.length < 3) { event.getChannel().sendMessage("Usage: /password " + sub + " <new_pass>").queue(); return; }
                        String pass = args[2];
                        if (pass.length() < 3 || pass.length() > 25) { event.getChannel().sendMessage(lang.plain("register.password-length")).queue(); return; }
                        String hash = SecurityUtils.hashPassword(pass, plugin.getPepper());
                        database.updatePassword(uuid, hash).thenRun(() -> {
                            event.getChannel().sendMessage(lang.plain("discord.password-changed-dm")).queue();
                            plugin.getLogManager().database(uuid, "PASSWORD_CHANGE_DM", record.get().username(), null);
                        });
                    } else if (sub.equals("delete") || sub.equals("удалить")) {
                        database.setPasswordEnabled(uuid, false).thenRun(() -> {
                            event.getChannel().sendMessage(lang.plain("discord.password-deleted-dm")).queue();
                            plugin.getLogManager().database(uuid, "PASSWORD_DELETE_DM", record.get().username(), null);
                        });
                    }
                });
            } else if (cmd.equals("/info") || cmd.equals("/инфо")) {
                database.findPlayerByDiscordId(discordId).thenAccept(record -> {
                    if (record.isEmpty()) { event.getChannel().sendMessage(lang.plain("discord.not-bound-dm")).queue(); return; }
                    DatabaseManager.PlayerRecord pr = record.get();
                    EmbedBuilder eb = new EmbedBuilder().setTitle(lang.plain("discord.info-title"))
                        .addField(lang.plain("discord.info-player"), pr.username(), true)
                        .addField(lang.plain("discord.info-status"), pr.locked() ? "❌ Locked" : "✅ Active", true)
                        .setColor(pr.locked() ? java.awt.Color.RED : java.awt.Color.GREEN);
                    event.getChannel().sendMessageEmbeds(eb.build()).queue();
                });
            } else if ((cmd.equals("/admin") || cmd.equals("/админ")) && isAdmin) {
                handleAdminCommand(event, args);
            } else if (cmd.equals("/help") || cmd.equals("/помощь")) {
                event.getChannel().sendMessage(buildHelpMessage(isAdmin)).queue();
            }
        }

        private void handleAdminCommand(MessageReceivedEvent event, String[] args) {
            if (args.length < 2) { event.getChannel().sendMessage("Usage: /admin <info|lock|unlock|reset|stats> [player]").queue(); return; }
            String sub = args[1].toLowerCase();
            switch (sub) {
                case "stats", "статистика" -> database.getStats().thenAccept(stats -> {
                    EmbedBuilder eb = new EmbedBuilder().setTitle("Server Stats")
                        .addField("Registered", String.valueOf(stats.registered()), true)
                        .addField("Locked", String.valueOf(stats.locked()), true)
                        .addField("Sessions", String.valueOf(stats.sessions()), true)
                        .setColor(java.awt.Color.CYAN);
                    event.getChannel().sendMessageEmbeds(eb.build()).queue();
                });
                case "info", "инфо", "lock", "заблокировать", "unlock", "разблокировать", "reset", "сброс" -> {
                    if (args.length < 3) { event.getChannel().sendMessage("Usage: /admin " + sub + " <player>").queue(); return; }
                    String target = args[2];
                    database.findPlayerByName(target).thenAccept(record -> {
                        if (record.isEmpty()) { event.getChannel().sendMessage("Player not found: " + target).queue(); return; }
                        DatabaseManager.PlayerRecord pr = record.get();
                        if (sub.equals("info") || sub.equals("инфо")) {
                            plugin.getDatabaseManager().getAlts(pr.lastIp()).thenAccept(alts -> {
                                EmbedBuilder eb = new EmbedBuilder().setTitle("Player Info: " + pr.username())
                                    .addField("UUID", pr.uuid().toString(), false)
                                    .addField("IP", pr.lastIp() != null ? pr.lastIp() : "---", true)
                                    .addField("Locked", String.valueOf(pr.locked()), true)
                                    .addField("Discord", pr.hasDiscord() ? "Linked" : "No", true)
                                    .addField("Alts", String.join(", ", alts), false)
                                    .setColor(java.awt.Color.ORANGE);
                                event.getChannel().sendMessageEmbeds(eb.build()).queue();
                            });
                        } else if (sub.equals("lock") || sub.equals("заблокировать")) {
                            database.setLocked(pr.uuid(), true).thenRun(() -> {
                                event.getChannel().sendMessage("Locked player " + target).queue();
                                plugin.getLogManager().database(pr.uuid(), "ADMIN_LOCK_DM", target, "Discord: " + event.getAuthor().getId());
                                Player p = Bukkit.getPlayer(pr.uuid());
                                if (p != null) Bukkit.getScheduler().runTask(plugin, () -> p.kick(lang.component("block.account-locked")));
                            });
                        } else if (sub.equals("unlock") || sub.equals("разблокировать")) {
                            database.setLocked(pr.uuid(), false).thenRun(() -> {
                                event.getChannel().sendMessage("Unlocked player " + target).queue();
                                plugin.getLogManager().database(pr.uuid(), "ADMIN_UNLOCK_DM", target, "Discord: " + event.getAuthor().getId());
                            });
                        } else if (sub.equals("reset") || sub.equals("сброс")) {
                            plugin.getSessionManager().invalidate(pr.uuid()).thenRun(() -> {
                                event.getChannel().sendMessage("Reset session for " + target).queue();
                                plugin.getLogManager().database(pr.uuid(), "ADMIN_SESSION_RESET_DM", target, "Discord: " + event.getAuthor().getId());
                                Player p = Bukkit.getPlayer(pr.uuid());
                                if (p != null) Bukkit.getScheduler().runTask(plugin, () -> p.kick(lang.component("commands.session-reset")));
                            });
                        }
                    });
                }
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
                event.getMessage().delete().queue();

                Bukkit.getScheduler().runTask(plugin, () -> {
                    Player player = Bukkit.getPlayer(uuid);
                    if (player != null && player.isOnline()) {
                        auth.markAuthenticated(player, true);
                        lang.send(player, "discord.login-approved");
                        plugin.getLogManager().database(uuid, "DISCORD_LOGIN_CONFIRM", player.getName(), player.getAddress().getAddress().getHostAddress());
                    }
                });
                event.reply(lang.plain("discord.login-confirmed-dm")).setEphemeral(true).queue();
            } else if (componentId.startsWith("login_deny:")) {
                UUID uuid = UUID.fromString(componentId.substring("login_deny:".length()));
                pendingLogins.remove(uuid);
                event.getMessage().delete().queue();

                Bukkit.getScheduler().runTask(plugin, () -> {
                    Player player = Bukkit.getPlayer(uuid);
                    if (player != null && player.isOnline()) {
                        plugin.getLogManager().database(uuid, "DISCORD_LOGIN_DENY", player.getName(), player.getAddress().getAddress().getHostAddress());
                        player.kick(lang.component("discord.login-denied-kick"));
                    }
                });
                event.reply(lang.plain("discord.login-denied-dm")).setEphemeral(true).queue();
            } else if (componentId.startsWith("action_lock:")) {
                UUID uuid = UUID.fromString(componentId.substring("action_lock:".length()));
                database.setLocked(uuid, true).thenRun(() -> {
                    Player p = Bukkit.getPlayer(uuid);
                    if (p != null) {
                        plugin.getLogManager().database(uuid, "ALERT_LOCK_DM", p.getName(), p.getAddress().getAddress().getHostAddress());
                        Bukkit.getScheduler().runTask(plugin, () -> p.kick(lang.component("block.account-locked")));
                    } else {
                        plugin.getLogManager().database(uuid, "ALERT_LOCK_DM", "Unknown", null);
                    }
                });
                event.getMessage().delete().queue();
                event.reply(lang.plain("discord.locked-dm")).setEphemeral(true).queue();
            }
        }
    }

    private String buildHelpMessage(boolean isAdmin) {
        StringBuilder sb = new StringBuilder();
        sb.append(lang.plain("discord.help-header")).append("\n")
          .append(lang.plain("discord.help-link")).append("\n")
          .append(lang.plain("discord.help-unlink")).append("\n")
          .append(lang.plain("discord.help-lock")).append("\n")
          .append(lang.plain("discord.help-unlock")).append("\n")
          .append(lang.plain("discord.help-password-change")).append("\n")
          .append(lang.plain("discord.help-password-delete")).append("\n")
          .append(lang.plain("discord.help-password-set")).append("\n")
          .append(lang.plain("discord.help-info")).append("\n");
        if (isAdmin) {
            sb.append("\n**Admin Commands:**\n")
              .append("`/admin stats` — server statistics\n")
              .append("`/admin info <player>` — player detailed info\n")
              .append("`/admin lock <player>` — manually lock player\n")
              .append("`/admin unlock <player>` — unlock player\n")
              .append("`/admin reset <player>` — reset player session\n");
        }
        sb.append(lang.plain("discord.help-help"));
        return sb.toString();
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
                    plugin.getLogManager().database(uuid, "DISCORD_LINK", player.getName(), player.getAddress().getAddress().getHostAddress());
                    auth.markAuthenticated(player, true);
                } else {
                    plugin.getLogManager().database(uuid, "DISCORD_LINK", "Unknown", null);
                }
            });
        }
    }

    public void handleBotUnlockCommand(String discordId) {
        database.findPlayerByDiscordId(discordId).thenAccept(record -> {
            if (record.isPresent()) {
                database.setLocked(record.get().uuid(), false).thenRun(() -> {
                    plugin.getLogger().info("[Discord] Account " + record.get().username() + " unlocked via Discord by " + discordId);
                    plugin.getLogManager().database(record.get().uuid(), "DISCORD_UNLOCK_CMD", record.get().username(), "Discord: " + discordId);
                });
            }
        });
    }
}
