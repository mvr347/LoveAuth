package me.lovelace.loveAuth.lang;

import me.lovelace.loveAuth.LoveAuth;
import me.lovelace.loveAuth.config.ConfigManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class LangManager {
    private final LoveAuth plugin;
    private final ConfigManager configManager;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private FileConfiguration lang;

    public LangManager(LoveAuth plugin, ConfigManager configManager) {
        this.plugin = plugin;
        this.configManager = configManager;
    }

    public void load() {
        File langDirectory = new File(plugin.getDataFolder(), "lang");
        if (!langDirectory.exists()) {
            langDirectory.mkdirs();
        }
        File langFile = new File(langDirectory, configManager.getLanguage() + ".yml");
        if (!langFile.exists()) {
            plugin.saveResource("lang/lang.yml", false);
        }
        lang = YamlConfiguration.loadConfiguration(langFile);
    }

    public void reload() {
        load();
    }

    public String get(String key) {
        return get(key, Collections.emptyMap());
    }

    public String get(String key, Map<String, String> placeholders) {
        String value = lang.getString(key, key);
        return resolve(value, placeholders);
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
        List<String> values = lang.getStringList(key);
        if (values.isEmpty() && lang.isString(key)) {
            values = List.of(lang.getString(key, key));
        }
        List<Component> components = new ArrayList<>(values.size());
        for (String value : values) {
            components.add(miniMessage.deserialize(resolve(value, placeholders)));
        }
        return components;
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

    public Map<String, String> placeholders(String... pairs) {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    private String resolve(String value, Map<String, String> placeholders) {
        String prefix = lang.getString("prefix", "");
        String resolved = value.replace("{prefix}", prefix);
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            resolved = resolved.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return resolved;
    }
}
