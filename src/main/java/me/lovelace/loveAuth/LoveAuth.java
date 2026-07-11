package me.lovelace.loveAuth;

import me.lovelace.loveAuth.api.LoveAuthAPI;
import me.lovelace.loveAuth.auth.AuthManager;
import me.lovelace.loveAuth.auth.BruteForceProtection;
import me.lovelace.loveAuth.commands.LAdminCommand;
import me.lovelace.loveAuth.commands.LoveAuthCommand;
import me.lovelace.loveAuth.config.ConfigManager;
import me.lovelace.loveAuth.database.DatabaseManager;
import me.lovelace.loveAuth.discord.DiscordAuthManager;
import me.lovelace.loveAuth.gui.GuiManager;
import me.lovelace.loveAuth.input.ChatInputHandler;
import me.lovelace.loveAuth.input.SignInputHandler;
import me.lovelace.loveAuth.lang.LangManager;
import me.lovelace.loveAuth.limbo.LimboManager;
import me.lovelace.loveAuth.listeners.*;
import me.lovelace.loveAuth.placeholder.LoveAuthExpansion;
import me.lovelace.loveAuth.premium.PremiumVerificationManager;
import me.lovelace.loveAuth.queue.QueueManager;
import me.lovelace.loveAuth.security.RateLimiter;
import me.lovelace.loveAuth.security.SecurityUtils;
import me.lovelace.loveAuth.session.SessionManager;
import me.lovelace.loveAuth.util.LogManager;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import javax.crypto.SecretKey;
import java.util.Map;

public final class LoveAuth extends JavaPlugin {
    private static LoveAuth instance;

    private ConfigManager configManager;
    private LangManager langManager;
    private LogManager logManager;
    private SecretKey masterKey;
    private String pepper;
    private DatabaseManager databaseManager;
    private SessionManager sessionManager;
    private RateLimiter rateLimiter;
    private BruteForceProtection bruteForceProtection;
    private LimboManager limboManager;
    private QueueManager queueManager;
    private AuthManager authManager;
    private GuiManager guiManager;
    private DiscordAuthManager discordAuthManager;
    private ChatInputHandler chatInputHandler;
    private SignInputHandler signInputHandler;
    private LAdminCommand lAdminCommand;
    private PremiumVerificationManager premiumVerificationManager;

    public static LoveAuth getInstance() { return instance; }

    @Override
    public void onEnable() {
        instance = this;

        configManager = new ConfigManager(this);
        configManager.load();

        masterKey = SecurityUtils.loadOrGenerateMasterKey(configManager);
        pepper = SecurityUtils.loadOrGeneratePepper(configManager);

        databaseManager = new DatabaseManager(this, configManager, masterKey);
        databaseManager.initialize();

        langManager = new LangManager(this, configManager);
        langManager.load();

        logManager = new LogManager(this, langManager);
        logManager.setDatabaseManager(databaseManager);
        configManager.consumeWarningKeys().forEach(logManager::warnKey);

        premiumVerificationManager = new PremiumVerificationManager(this);
        premiumVerificationManager.initialize();

        sessionManager = new SessionManager(this, configManager, databaseManager, masterKey);
        rateLimiter = new RateLimiter();
        bruteForceProtection = new BruteForceProtection(this, configManager, databaseManager, masterKey, logManager);
        limboManager = new LimboManager(this, configManager, langManager, logManager);
        limboManager.initialize();
        queueManager = new QueueManager(this, configManager, langManager);
        queueManager.start();
        authManager = new AuthManager(this, configManager, langManager, databaseManager, sessionManager, bruteForceProtection, limboManager, logManager, masterKey, pepper);
        discordAuthManager = new DiscordAuthManager(this, configManager, langManager, databaseManager, authManager);
        discordAuthManager.initialize();
        chatInputHandler = new ChatInputHandler(this, langManager);
        signInputHandler = new SignInputHandler(this);
        guiManager = new GuiManager(this, langManager, configManager, authManager, queueManager, discordAuthManager, chatInputHandler, signInputHandler);

        registerListeners();
        registerCommands();
        LoveAuthAPI.setInstance(new LoveAuthAPI(this));

        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            new LoveAuthExpansion(this).register();
            logManager.infoKey("log.placeholder-registered");
        }

        logManager.infoKey("log.plugin-enable", Map.of("version", getDescription().getVersion()));
    }

    @Override
    public void onDisable() {
        if (sessionManager != null) {
            try {
                // Block until pending session writes land before the connection pool
                // below is closed - otherwise they'd race databaseManager.close() and
                // fail with "pool has been shutdown" during server stop.
                sessionManager.saveActiveSessions().get(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception e) {
                if (logManager != null) {
                    logManager.errorKey("log.error", Map.of("message", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()), e);
                }
            }
        }
        if (queueManager != null) queueManager.stop();
        if (premiumVerificationManager != null) premiumVerificationManager.shutdown();
        if (discordAuthManager != null) discordAuthManager.shutdown();
        if (databaseManager != null) databaseManager.close();
        instance = null;
    }

    private void registerListeners() {
        PluginManager pm = Bukkit.getPluginManager();
        pm.registerEvents(new PlayerLoginListener(this), this);
        pm.registerEvents(new PlayerJoinListener(this), this);
        pm.registerEvents(new PlayerQuitListener(this), this);
        pm.registerEvents(new PlayerProtectionListener(this), this);
        pm.registerEvents(new GuiClickListener(this), this);
        pm.registerEvents(new GuiCloseListener(this), this);
        pm.registerEvents(chatInputHandler, this);
        pm.registerEvents(signInputHandler, this);
    }

    private void registerCommands() {
        LoveAuthCommand cmd = new LoveAuthCommand(this);
        getCommand("loveauth").setExecutor(cmd);
        getCommand("loveauth").setTabCompleter(cmd);
        lAdminCommand = new LAdminCommand(this);
        getCommand("ladmin").setExecutor(lAdminCommand);
        getCommand("ladmin").setTabCompleter(lAdminCommand);
    }

    public ConfigManager getConfigManager() { return configManager; }
    public LangManager getLangManager() { return langManager; }
    public LogManager getLogManager() { return logManager; }
    public DatabaseManager getDatabaseManager() { return databaseManager; }
    public SessionManager getSessionManager() { return sessionManager; }
    public RateLimiter getRateLimiter() { return rateLimiter; }
    public BruteForceProtection getBruteForceProtection() { return bruteForceProtection; }
    public LimboManager getLimboManager() { return limboManager; }
    public QueueManager getQueueManager() { return queueManager; }
    public String getPepper() { return pepper; }
    public AuthManager getAuthManager() { return authManager; }
    public GuiManager getGuiManager() { return guiManager; }
    public DiscordAuthManager getDiscordAuthManager() { return discordAuthManager; }
    public ChatInputHandler getChatInputHandler() { return chatInputHandler; }
    public SignInputHandler getSignInputHandler() { return signInputHandler; }
    public LAdminCommand getLAdminCommand() { return lAdminCommand; }
    public PremiumVerificationManager getPremiumVerificationManager() { return premiumVerificationManager; }
}
