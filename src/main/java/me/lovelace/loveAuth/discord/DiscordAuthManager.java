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
import java.util.Base64;
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
    private final Map<UUID, AdminAction> pendingAdminActions = new ConcurrentHashMap<>();
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
        plugin.getServer().getAsyncScheduler().runDelayed(plugin, t -> pendingLinks.remove(code), 10, TimeUnit.MINUTES);
        net.kyori.adventure.text.Component msg = net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(lang.get("discord.bind-instructions", Map.of("code", code)));
        player.sendMessage(msg);
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
                    Button confirmBtn = Button.success("login_confirm:" + player.getUniqueId(), lang.plain("discord.login-btn-confirm"));
                    Button denyBtn = Button.danger("login_deny:" + player.getUniqueId(), lang.plain("discord.login-btn-deny"));
                    channel.sendMessageEmbeds(embed).setComponents(ActionRow.of(confirmBtn, denyBtn)).queue(msg -> {
                        pendingLogins.put(player.getUniqueId(), msg.getIdLong());
                        lang.send(player, "discord.login-dm-sent");
                        result.complete(true);
                        plugin.getServer().getAsyncScheduler().runDelayed(plugin, t -> {
                            if (pendingLogins.remove(player.getUniqueId()) != null) {
                                Bukkit.getScheduler().runTask(plugin, () -> {
                                    if (player.isOnline() && !auth.isAuthenticated(player.getUniqueId())) player.kick(lang.component("discord.login-timeout"));
                                });
                                msg.delete().queue();
                            }
                        }, 2, TimeUnit.MINUTES);
                    }, error -> result.complete(false));
                }, error -> result.complete(false));
            }, error -> result.complete(false));
        } catch (Exception e) { result.complete(false); }
        return result;
    }

    public void sendConfirmation(Player player, String action) {
        if (!isEnabled()) { lang.send(player, "discord.not-enabled"); return; }
        database.findPlayer(player.getUniqueId()).thenAccept(record -> {
            if (record.isEmpty() || !record.get().hasDiscord()) { lang.send(player, "discord.not-bound"); return; }
            jda.retrieveUserById(record.get().discordId()).queue(user -> user.openPrivateChannel().queue(channel -> {
                MessageEmbed embed = new EmbedBuilder().setTitle(lang.plain("discord.confirm-action-title")).setDescription(lang.plain("discord.action-" + action.toLowerCase())).setColor(java.awt.Color.decode("#a855f7")).setFooter(lang.plain("discord.confirm-action-footer")).build();
                Button confirm = Button.success("confirm_action:" + player.getUniqueId() + ":" + action, lang.plain("discord.action-btn-confirm"));
                Button deny = Button.danger("deny_action:" + player.getUniqueId() + ":" + action, lang.plain("discord.action-btn-deny"));
                Button lockBtn = Button.danger("action_lock:" + player.getUniqueId(), lang.plain("discord.lock-btn-alert"));
                channel.sendMessageEmbeds(embed).setComponents(ActionRow.of(confirm, deny), ActionRow.of(lockBtn)).queue(msg -> {
                    lang.send(player, "discord.confirmation-sent-buttons");
                    msg.delete().queueAfter(2, TimeUnit.MINUTES, null, err -> {});
                });
            }));
        });
    }

    public CompletableFuture<Boolean> requestAdminConfirmation(Player player, String[] args) {
        if (!isEnabled()) return CompletableFuture.completedFuture(false);
        return database.findPlayer(player.getUniqueId()).thenCompose(record -> {
            if (record.isEmpty() || !record.get().hasDiscord()) return CompletableFuture.completedFuture(false);
            CompletableFuture<Boolean> res = new CompletableFuture<>();
            jda.retrieveUserById(record.get().discordId()).queue(user -> user.openPrivateChannel().queue(channel -> {
                MessageEmbed em = new EmbedBuilder().setTitle(lang.plain("discord.admin-embed-title")).setDescription(lang.plain("discord.admin-embed-desc", Map.of("cmd", "/ladmin " + String.join(" ", args)))).setColor(java.awt.Color.RED).build();
                String argsBase = Base64.getEncoder().encodeToString(String.join(" ", args).getBytes());
                Button ok = Button.success("admin_confirm:" + player.getUniqueId() + ":" + argsBase, lang.plain("discord.action-btn-confirm"));
                Button no = Button.danger("admin_deny:" + player.getUniqueId(), lang.plain("discord.action-btn-deny"));
                channel.sendMessageEmbeds(em).setComponents(ActionRow.of(ok, no)).queue(msg -> {
                    pendingAdminActions.put(player.getUniqueId(), new AdminAction(args));
                    res.complete(true);
                    msg.delete().queueAfter(2, TimeUnit.MINUTES, null, err -> {});
                }, e -> res.complete(false));
            }, e -> res.complete(false)), e -> res.complete(false));
            return res;
        });
    }

    private void handleConfirmedAction(Player player, String action) {
        switch (action) {
            case "UNLINK" -> database.setDiscordId(player.getUniqueId(), null).thenRun(() -> { lang.send(player, "discord.unlinked"); plugin.getLogManager().database(player.getUniqueId(), "DISCORD_UNLINK", player.getName(), player.getAddress().getAddress().getHostAddress()); });
            case "REMOVE_PASSWORD" -> database.setPasswordEnabled(player.getUniqueId(), false).thenRun(() -> { lang.send(player, "commands.password-removed"); plugin.getLogManager().database(player.getUniqueId(), "PASSWORD_DELETE", player.getName(), player.getAddress().getAddress().getHostAddress()); });
            case "LOCK_ACCOUNT" -> database.setLocked(player.getUniqueId(), true).thenRun(() -> { lang.send(player, "discord.account-locked-self"); plugin.getLogManager().database(player.getUniqueId(), "MANUAL_LOCK", player.getName(), player.getAddress().getAddress().getHostAddress()); Bukkit.getScheduler().runTask(plugin, () -> player.kick(lang.component("block.account-locked"))); });
        }
    }

    private void handleConfirmedActionOffline(UUID u, String a) {
        if (a.equals("UNLINK")) database.setDiscordId(u, null);
        else if (a.equals("LOCK_ACCOUNT")) database.setLocked(u, true);
    }

    private class DiscordEventListener extends ListenerAdapter {
        @Override
        public void onMessageReceived(MessageReceivedEvent e) {
            if (e.getAuthor().isBot() || !e.isFromType(ChannelType.PRIVATE)) return;
            String[] args = e.getMessage().getContentRaw().trim().split("\\s+");
            String dId = e.getAuthor().getId();
            boolean isAdmin = config.getDiscordAdminIds().contains(dId);
            String cmd = args[0].toLowerCase();
            if (cmd.equals("/link") || cmd.equals("/привязать")) {
                if (args.length < 2) { e.getChannel().sendMessage(lang.plain("discord.link-usage")).queue(); return; }
                UUID uuid = pendingLinks.remove(args[1].toUpperCase());
                if (uuid == null) { e.getChannel().sendMessage(lang.plain("discord.link-invalid-code")).queue(); return; }
                database.findPlayerByDiscordId(dId).thenAccept(ex -> {
                    if (ex.isPresent() && !ex.get().uuid().equals(uuid)) { e.getChannel().sendMessage(lang.plain("discord.already-bound-other")).queue(); return; }
                    database.setDiscordId(uuid, dId).thenRun(() -> { e.getChannel().sendMessage(lang.plain("discord.link-success-dm")).queue(); Player p = Bukkit.getPlayer(uuid); if (p != null) { lang.send(p, "discord.bind-success"); plugin.getLogManager().database(uuid, "DISCORD_LINK", p.getName(), p.getAddress().getAddress().getHostAddress()); auth.markAuthenticated(p, true); lang.sendActionBar(p, "actionbar.discord-bound", Map.of()); } });
                });
            } else if (cmd.equals("/unlink") || cmd.equals("/отвязать")) { database.findPlayerByDiscordId(dId).thenAccept(r -> { if (r.isEmpty()) { e.getChannel().sendMessage(lang.plain("discord.not-bound-dm")).queue(); return; } database.setDiscordId(r.get().uuid(), null).thenRun(() -> { e.getChannel().sendMessage(lang.plain("discord.unlinked-dm")).queue(); }); });
            } else if (cmd.equals("/lock") || cmd.equals("/заблокировать")) { database.findPlayerByDiscordId(dId).thenAccept(r -> { if (r.isEmpty()) { e.getChannel().sendMessage(lang.plain("discord.not-bound-dm")).queue(); return; } database.setLocked(r.get().uuid(), true).thenRun(() -> { e.getChannel().sendMessage(lang.plain("discord.locked-dm")).queue(); Player p = Bukkit.getPlayer(r.get().uuid()); if (p != null) Bukkit.getScheduler().runTask(plugin, () -> p.kick(lang.component("block.account-locked"))); }); });
            } else if (cmd.equals("/unlock") || cmd.equals("/разблокировать")) { database.findPlayerByDiscordId(dId).thenAccept(r -> { if (r.isEmpty()) { e.getChannel().sendMessage(lang.plain("discord.not-bound-dm")).queue(); return; } database.setLocked(r.get().uuid(), false).thenRun(() -> e.getChannel().sendMessage(lang.plain("discord.unlocked-dm")).queue()); });
            } else if (cmd.equals("/password") || cmd.equals("/пароль")) {
                if (args.length < 2) { e.getChannel().sendMessage("Usage: /password <change|delete|set> [pass]").queue(); return; }
                database.findPlayerByDiscordId(dId).thenAccept(r -> {
                    if (r.isEmpty()) { e.getChannel().sendMessage(lang.plain("discord.not-bound-dm")).queue(); return; }
                    String sub = args[1].toLowerCase();
                    if (sub.equals("change") || sub.equals("set") || sub.equals("изменить") || sub.equals("установить")) {
                        if (args.length < 3) return;
                        if (args[2].length() < 3 || args[2].length() > 25) { e.getChannel().sendMessage(lang.plain("register.password-length")).queue(); return; }
                        database.updatePassword(r.get().uuid(), SecurityUtils.hashPassword(args[2], plugin.getPepper())).thenRun(() -> e.getChannel().sendMessage(lang.plain("discord.password-changed-dm")).queue());
                    } else if (sub.equals("delete") || sub.equals("удалить")) database.setPasswordEnabled(r.get().uuid(), false).thenRun(() -> e.getChannel().sendMessage(lang.plain("discord.password-deleted-dm")).queue());
                });
            } else if (cmd.equals("/info") || cmd.equals("/инфо")) { database.findPlayerByDiscordId(dId).thenAccept(r -> { if (r.isEmpty()) { e.getChannel().sendMessage(lang.plain("discord.not-bound-dm")).queue(); return; } DatabaseManager.PlayerRecord pr = r.get(); plugin.getDatabaseManager().getAlts(pr.lastIp()).thenAccept(alts -> { EmbedBuilder eb = new EmbedBuilder().setTitle(lang.plain("discord.info-title")).addField(lang.plain("discord.info-player"), pr.username(), true).addField(lang.plain("discord.info-status"), pr.locked() ? "Locked" : "Active", true).addField("Alts", String.join(", ", alts), false).setColor(pr.locked() ? java.awt.Color.RED : java.awt.Color.GREEN); e.getChannel().sendMessageEmbeds(eb.build()).queue(); }); });
            } else if ((cmd.equals("/admin") || cmd.equals("/админ") || cmd.equals("/ladmin") || cmd.equals("/админка")) && isAdmin) handleAdminCommand(e, args);
            else if (cmd.equals("/help") || cmd.equals("/помощь")) e.getChannel().sendMessage(buildHelpMessage(isAdmin)).queue();
        }

        private void handleAdminCommand(MessageReceivedEvent e, String[] args) {
            if (args.length < 2) return;
            String sub = args[1].toLowerCase();
            if (sub.equals("stats") || sub.equals("статистика")) database.getStats().thenAccept(s -> { EmbedBuilder eb = new EmbedBuilder().setTitle("Stats").addField("Reg", String.valueOf(s.registered()), true).addField("Lock", String.valueOf(s.locked()), true).addField("Sess", String.valueOf(s.sessions()), true).setColor(java.awt.Color.CYAN); e.getChannel().sendMessageEmbeds(eb.build()).queue(); });
            else if (sub.equals("amnesty") || sub.equals("амнистия")) database.clearAllIpBlocks().thenCompose(u -> database.unlockAllAccounts()).thenRun(() -> e.getChannel().sendMessage("Amnesty done.").queue());
            else if (args.length >= 3) {
                database.findPlayerByName(args[2]).thenAccept(r -> {
                    if (r.isEmpty()) return;
                    DatabaseManager.PlayerRecord pr = r.get();
                    if (sub.equals("info") || sub.equals("инфо")) plugin.getDatabaseManager().getAlts(pr.lastIp()).thenAccept(alts -> { EmbedBuilder eb = new EmbedBuilder().setTitle("Info: " + pr.username()).addField("UUID", pr.uuid().toString(), false).addField("Alts", String.join(", ", alts), false).setColor(java.awt.Color.ORANGE); e.getChannel().sendMessageEmbeds(eb.build()).queue(); });
                    else if (sub.equals("lock") || sub.equals("заблокировать")) database.setLocked(pr.uuid(), true).thenRun(() -> { e.getChannel().sendMessage("Locked " + args[2]).queue(); Player p = Bukkit.getPlayer(pr.uuid()); if (p != null) Bukkit.getScheduler().runTask(plugin, () -> p.kick(lang.component("block.account-locked"))); });
                    else if (sub.equals("unlock") || sub.equals("разблокировать")) database.setLocked(pr.uuid(), false).thenRun(() -> e.getChannel().sendMessage("Unlocked " + args[2]).queue());
                    else if (sub.equals("reset") || sub.equals("сброс")) plugin.getSessionManager().invalidate(pr.uuid()).thenRun(() -> { e.getChannel().sendMessage("Reset " + args[2]).queue(); Player p = Bukkit.getPlayer(pr.uuid()); if (p != null) Bukkit.getScheduler().runTask(plugin, () -> p.kick(lang.component("commands.session-reset"))); });
                });
            }
        }

        @Override
        public void onButtonInteraction(ButtonInteractionEvent e) {
            String id = e.getComponentId();
            if (id.startsWith("login_confirm:")) { UUID u = UUID.fromString(id.split(":")[1]); if (pendingLogins.remove(u) != null) { e.getMessage().delete().queue(); Bukkit.getScheduler().runTask(plugin, () -> { Player p = Bukkit.getPlayer(u); if (p != null) { auth.markAuthenticated(p, true); lang.send(p, "discord.login-approved"); } }); e.reply("Confirmed.").setEphemeral(true).queue(); } }
            else if (id.startsWith("login_deny:")) { UUID u = UUID.fromString(id.split(":")[1]); pendingLogins.remove(u); e.getMessage().delete().queue(); Bukkit.getScheduler().runTask(plugin, () -> { Player p = Bukkit.getPlayer(u); if (p != null) p.kick(lang.component("discord.login-denied-kick")); }); e.reply("Denied.").setEphemeral(true).queue(); }
            else if (id.startsWith("action_lock:")) { UUID u = UUID.fromString(id.split(":")[1]); database.setLocked(u, true).thenRun(() -> { e.getMessage().delete().queue(); Player p = Bukkit.getPlayer(u); if (p != null) Bukkit.getScheduler().runTask(plugin, () -> p.kick(lang.component("block.account-locked"))); }); e.reply("Locked.").setEphemeral(true).queue(); }
            else if (id.startsWith("confirm_action:")) { String[] p = id.split(":"); UUID u = UUID.fromString(p[1]); e.getMessage().delete().queue(); Bukkit.getScheduler().runTask(plugin, () -> { Player pl = Bukkit.getPlayer(u); if (pl != null) handleConfirmedAction(pl, p[2]); else handleConfirmedActionOffline(u, p[2]); }); e.reply("Confirmed.").setEphemeral(true).queue(); }
            else if (id.startsWith("deny_action:")) { e.getMessage().delete().queue(); e.reply("Cancelled.").setEphemeral(true).queue(); }
            else if (id.startsWith("admin_confirm:")) { String[] p = id.split(":"); UUID u = UUID.fromString(p[1]); String a = new String(Base64.getDecoder().decode(p[2])); e.getMessage().delete().queue(); Bukkit.getScheduler().runTask(plugin, () -> { Player pl = Bukkit.getPlayer(u); if (pl != null) plugin.getLAdminCommand().handleCommand(pl, a.split(" ")); }); e.reply("Admin action confirmed.").setEphemeral(true).queue(); }
            else if (id.startsWith("admin_deny:")) { e.getMessage().delete().queue(); e.reply("Cancelled.").setEphemeral(true).queue(); }
        }
    }

    private String buildHelpMessage(boolean a) {
        StringBuilder sb = new StringBuilder().append(lang.plain("discord.help-header")).append("\n").append(lang.plain("discord.help-link")).append("\n").append(lang.plain("discord.help-unlink")).append("\n").append(lang.plain("discord.help-lock")).append("\n").append(lang.plain("discord.help-unlock")).append("\n").append(lang.plain("discord.help-password-change")).append("\n").append(lang.plain("discord.help-password-delete")).append("\n").append(lang.plain("discord.help-password-set")).append("\n").append(lang.plain("discord.help-info")).append("\n");
        if (a) sb.append("\nAdmin:\n/admin stats\n/admin amnesty\n/admin lock <p>\n/admin unlock <p>\n/admin info <p>\n/admin reset <p>");
        return sb.append(lang.plain("discord.help-help")).toString();
    }

    private String generateCode(int l) { String c = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; StringBuilder s = new StringBuilder(); for (int i = 0; i < l; i++) s.append(c.charAt(random.nextInt(c.length()))); return s.toString(); }
    private String generateNumericCode(int l) { String c = "ABCDEFGHJKLMNPQRSTUVWXYZ0123456789"; StringBuilder s = new StringBuilder(); for (int i = 0; i < l; i++) s.append(c.charAt(random.nextInt(c.length()))); return s.toString(); }
    private record AdminAction(String[] args) {}

    public void handleBotLinkCommand(String c, String d) {
        UUID u = pendingLinks.remove(c);
        if (u != null) database.findPlayerByDiscordId(d).thenAccept(ex -> {
            if (ex.isPresent()) return;
            database.setDiscordId(u, d).thenRun(() -> { Player p = Bukkit.getPlayer(u); if (p != null) { lang.send(p, "discord.bind-success"); plugin.getLogManager().database(u, "DISCORD_LINK", p.getName(), p.getAddress().getAddress().getHostAddress()); auth.markAuthenticated(p, true); } });
        });
    }

    public void handleBotUnlockCommand(String d) { database.findPlayerByDiscordId(d).thenAccept(r -> { if (r.isPresent()) database.setLocked(r.get().uuid(), false); }); }
}
