package me.lovelace.loveAuth.gui;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import me.lovelace.loveAuth.LoveAuth;
import me.lovelace.loveAuth.auth.AuthManager;
import me.lovelace.loveAuth.config.ConfigManager;
import me.lovelace.loveAuth.discord.DiscordAuthManager;
import me.lovelace.loveAuth.input.ChatInputHandler;
import me.lovelace.loveAuth.input.InputMethod;
import me.lovelace.loveAuth.input.SignInputHandler;
import me.lovelace.loveAuth.lang.LangManager;
import me.lovelace.loveAuth.queue.QueueManager;
import me.lovelace.loveAuth.util.HeadTextures;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.time.Duration;
import java.util.Map;
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
    private final SignInputHandler signInput;
    
    private final Cache<UUID, Long> cooldowns = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMillis(800))
            .build();

    public GuiManager(
            LoveAuth plugin,
            LangManager lang,
            ConfigManager config,
            AuthManager auth,
            QueueManager queue,
            DiscordAuthManager discord,
            ChatInputHandler chatInput,
            SignInputHandler signInput
    ) {
        this.plugin = plugin;
        this.lang = lang;
        this.config = config;
        this.auth = auth;
        this.queue = queue;
        this.discord = discord;
        this.chatInput = chatInput;
        this.signInput = signInput;
    }

    public void openAuthMethod(Player player) { if (!checkCooldown(player)) new AuthMethodGui(player, lang, config, auth, discord).open(); }
    public void openPassword(Player player) { if (!checkCooldown(player)) new PasswordGui(player, lang, auth).open(); }
    public void openDiscord(Player player) { if (!checkCooldown(player)) new DiscordGui(player, lang, config, discord, auth).open(); }
    public void openRegister(Player player) { if (!checkCooldown(player)) new RegisterGui(player, lang, auth).open(); }
    public void openPremiumWelcome(Player player) { if (!checkCooldown(player)) new PremiumWelcomeGui(player, lang, config, discord, auth).open(); }
    public void openAccount(Player player) { if (!checkCooldown(player)) new AccountGui(player, lang, auth).open(); }
    public void openAccount(Player player, String returnCommand) { if (!checkCooldown(player)) new AccountGui(player, lang, auth, returnCommand).open(); }
    public void openQueue(Player player) { if (!checkCooldown(player)) new QueueGui(player, lang, queue).open(); }
    public void openAdmin(Player player) { if (!checkCooldown(player)) new AdminGui(player, plugin, lang, auth).open(); }

    public void openConfirm(Player player, String descriptionKey, Runnable onConfirm, Runnable onCancel) {
        if (!checkCooldown(player)) new ConfirmGui(player, lang, descriptionKey, onConfirm, onCancel).open();
    }

    public void awaitInput(Player player, InputMethod method, String promptKey, Consumer<String> callback) {
        player.closeInventory();
        if (method == InputMethod.SIGN && signInput.canUseSignAt(player)) {
            signInput.awaitInput(player, new String[]{lang.plain(promptKey), "^^^^^^^^^^^^^^^", "---------------", "               "}, lines -> {
                if (lines[0] != null && !lines[0].isBlank()) callback.accept(lines[0]);
            });
        } else {
            chatInput.awaitInput(player, promptKey, callback);
        }
    }

    public boolean checkCooldown(Player player) {
        if (cooldowns.getIfPresent(player.getUniqueId()) != null) return true;
        cooldowns.put(player.getUniqueId(), System.currentTimeMillis());
        return false;
    }

    public static void fillBackground(Inventory inventory, LangManager lang) {
        ItemStack filler = glassFiller(lang);
        for (int i = 0; i < inventory.getSize(); i++) inventory.setItem(i, filler);
    }

    /**
     * Рамка стандартного 27-слотового меню gui_gen: сплошное стекло в верхней (1-8) и
     * нижней (18-24) полосе. Слоты 9-17 намеренно НЕ трогаются — это рабочая зона, где
     * вызывающий код расставляет кнопки впритык; неиспользуемые слоты там остаются
     * буквально пустыми (не стекло), как в эталонном player_profile.yml.
     */
    public static void standardFrame27(Inventory inventory, LangManager lang) {
        ItemStack filler = glassFiller(lang);
        for (int i = 1; i <= 8; i++) inventory.setItem(i, filler);
        for (int i = 18; i <= 24; i++) inventory.setItem(i, filler);
    }

    private static ItemStack glassFiller(LangManager lang) {
        ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = filler.getItemMeta();
        if (meta != null) {
            meta.displayName(lang.component("gui.filler-name"));
            filler.setItemMeta(meta);
        }
        return filler;
    }

    /** Слот 0 (gui_gen standard): голова самого игрока — LoveAuth всегда открывает "прямой" профиль. */
    public static ItemStack playerHead(Player player, LangManager lang) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        if (meta != null) {
            meta.setOwningPlayer(player);
            meta.displayName(lang.component("gui.player-head", Map.of("player", player.getName())));
            head.setItemMeta(meta);
        }
        return head;
    }

    /** Заполняет footer-слоты 25/26 стандартного 27-слотового меню gui_gen: Back (опционально) + Close. */
    public static void applyFooter27(Inventory inventory, LangManager lang, boolean withBack) {
        if (withBack) {
            inventory.setItem(25, HeadTextures.createSkull(HeadTextures.HEAD_BACK,
                    lang.component("gui.back-button"), java.util.Collections.emptyList()));
        } else {
            inventory.setItem(25, glassFiller(lang));
        }
        inventory.setItem(26, HeadTextures.createSkull(HeadTextures.HEAD_BARRIER,
                lang.component("gui.close-button"), java.util.Collections.emptyList()));
    }
}
