package me.lovelace.loveAuth.gui;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import me.lovelace.loveAuth.LoveAuth;
import me.lovelace.loveAuth.auth.AuthManager;
import me.lovelace.loveAuth.config.ConfigManager;
import me.lovelace.loveAuth.discord.DiscordAuthManager;
import me.lovelace.loveAuth.input.AnvilInputHandler;
import me.lovelace.loveAuth.input.ChatInputHandler;
import me.lovelace.loveAuth.input.InputMethod;
import me.lovelace.loveAuth.lang.LangManager;
import me.lovelace.loveAuth.queue.QueueManager;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.time.Duration;
import java.util.UUID;
import java.util.function.Consumer;

public final class GuiManager {
    private final LoveAuth plugin;
    private final LangManager lang;
    private final ConfigManager config;
    private final AuthManager auth;
    private final QueueManager queue;
    private final DiscordAuthManager discord;
    private final ChatInputHandler chatInput;
    private final AnvilInputHandler anvilInput;
    
    // Cooldown with multi-click protection
    private final Cache<UUID, Long> cooldowns = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMillis(800)) // Increased cooldown
            .build();

    public GuiManager(
            LoveAuth plugin,
            LangManager lang,
            ConfigManager config,
            AuthManager auth,
            QueueManager queue,
            DiscordAuthManager discord,
            ChatInputHandler chatInput,
            AnvilInputHandler anvilInput
    ) {
        this.plugin = plugin;
        this.lang = lang;
        this.config = config;
        this.auth = auth;
        this.queue = queue;
        this.discord = discord;
        this.chatInput = chatInput;
        this.anvilInput = anvilInput;
    }

    public void openAuthMethod(Player player) { if (!checkCooldown(player)) new AuthMethodGui(player, lang, config, auth, discord).open(); }
    public void openPassword(Player player) { if (!checkCooldown(player)) new PasswordGui(player, lang, auth).open(); }
    public void openDiscord(Player player) { if (!checkCooldown(player)) new DiscordGui(player, lang, config, discord, auth).open(); }
    public void openRegister(Player player) { if (!checkCooldown(player)) new RegisterGui(player, lang, auth).open(); }
    public void openPremiumWelcome(Player player) { if (!checkCooldown(player)) new PremiumWelcomeGui(player, lang, config, discord, auth).open(); }
    public void openAccount(Player player) { if (!checkCooldown(player)) new AccountGui(player, lang, auth).open(); }
    public void openQueue(Player player) { if (!checkCooldown(player)) new QueueGui(player, lang, queue).open(); }
    public void openAdmin(Player player) { if (!checkCooldown(player)) new AdminGui(player, plugin, lang, auth).open(); }

    public void openConfirm(Player player, String descriptionKey, Runnable onConfirm, Runnable onCancel) {
        if (!checkCooldown(player)) new ConfirmGui(player, lang, descriptionKey, onConfirm, onCancel).open();
    }

    public void awaitInput(Player player, InputMethod method, String promptKey, Consumer<String> callback) {
        player.closeInventory();
        if (method == InputMethod.ANVIL) anvilInput.awaitInput(player, promptKey, callback);
        else chatInput.awaitInput(player, promptKey, callback);
    }

    public boolean checkCooldown(Player player) {
        if (cooldowns.getIfPresent(player.getUniqueId()) != null) return true;
        cooldowns.put(player.getUniqueId(), System.currentTimeMillis());
        return false;
    }

    public static void fillBackground(Inventory inventory, LangManager lang) {
        ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = filler.getItemMeta();
        if (meta != null) {
            meta.displayName(lang.component("gui.filler-name"));
            filler.setItemMeta(meta);
        }
        for (int i = 0; i < inventory.getSize(); i++) inventory.setItem(i, filler);
    }
}
