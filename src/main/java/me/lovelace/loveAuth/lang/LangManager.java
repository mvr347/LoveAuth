package me.lovelace.loveAuth.lang;

import me.lovelace.loveAuth.config.ConfigManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public final class LangManager {
    private final JavaPlugin plugin;
    private final ConfigManager config;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private YamlConfiguration lang;

    public LangManager(JavaPlugin plugin, ConfigManager config) {
        this.plugin = plugin;
        this.config = config;
    }

    public void load() {
        String langName = config.getLanguage() + ".yml";
        File file = new File(plugin.getDataFolder(), "lang/" + langName);
        if (!file.exists()) {
            try {
                plugin.saveResource("lang/" + langName, false);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Language file 'lang/" + langName + "' is not bundled with the plugin, falling back to default lang.yml");
                plugin.saveResource("lang/lang.yml", false);
                file = new File(plugin.getDataFolder(), "lang/lang.yml");
            }
        }
        lang = YamlConfiguration.loadConfiguration(file);
        mergeMissingKeys(file);
    }

    /**
     * Existing installs keep their lang file as-is across updates, so keys added by a
     * newer plugin version (e.g. new lang.* entries) are silently missing and render as
     * the raw key instead of text. Backfill anything present in the bundled default that
     * the on-disk file doesn't have yet, without touching the user's existing edits.
     */
    private void mergeMissingKeys(File file) {
        try (InputStream defaultStream = plugin.getResource("lang/lang.yml")) {
            if (defaultStream == null) return;
            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(defaultStream, StandardCharsets.UTF_8));
            boolean changed = false;
            for (String key : defaults.getKeys(true)) {
                if (defaults.isConfigurationSection(key)) continue;
                if (!lang.isSet(key)) {
                    lang.set(key, defaults.get(key));
                    changed = true;
                }
            }
            if (changed) lang.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to merge missing lang keys: " + e.getMessage());
        }
    }

    public String get(String key) {
        return get(key, Collections.emptyMap());
    }

    public String get(String key, Map<String, String> placeholders) {
        String message = lang.getString(key, key);
        String prefix = lang.getString("prefix", "");
        message = message.replace("{prefix}", prefix);
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            message = message.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return message;
    }

    public Component component(String key) {
        return component(key, Collections.emptyMap());
    }

    public Component component(String key, Map<String, String> placeholders) {
        return miniMessage.deserialize(get(key, placeholders));
    }

    public List<Component> lore(String key) {
        return lore(key, Collections.emptyMap());
    }

    public List<Component> lore(String key, Map<String, String> placeholders) {
        List<String> lines = lang.getStringList(key);
        if (lines.isEmpty()) {
            return Collections.singletonList(component(key, placeholders));
        }
        return lines.stream().map(line -> {
            for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                line = line.replace("{" + entry.getKey() + "}", entry.getValue());
            }
            return miniMessage.deserialize(line);
        }).toList();
    }

    public String plain(String key) {
        return plain(key, Collections.emptyMap());
    }

    public String plain(String key, Map<String, String> placeholders) {
        return PlainTextComponentSerializer.plainText().serialize(component(key, placeholders));
    }

    public void send(CommandSender sender, String key) {
        send(sender, key, Collections.emptyMap());
    }

    public void send(CommandSender sender, String key, Map<String, String> placeholders) {
        sender.sendMessage(component(key, placeholders));
    }
    
    public void showTitle(Player player, String mainKey, String subKey) {
        Title title = Title.title(
            component(mainKey),
            component(subKey),
            Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(3), Duration.ofMillis(500))
        );
        player.showTitle(title);
    }

    public void sendActionBar(Player player, String key, Map<String, String> placeholders) {
        player.sendActionBar(component(key, placeholders));
    }
}
