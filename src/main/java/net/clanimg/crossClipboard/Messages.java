package net.clanimg.crossClipboard;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Player-facing text from {@code plugins/CrossClipboard/lang/<language>.yml}, written in MiniMessage. Keys
 * missing from an edited file fall back to the bundled English text.
 */
public final class Messages {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final JavaPlugin plugin;
    private volatile YamlConfiguration lang;

    public Messages(JavaPlugin plugin, String language) {
        this.plugin = plugin;
        reload(language);
    }

    public void reload(String language) {
        YamlConfiguration english = bundled("en");
        File file = new File(plugin.getDataFolder(), "lang/" + language + ".yml");
        if (!file.exists() && plugin.getResource("lang/" + language + ".yml") != null) {
            plugin.saveResource("lang/" + language + ".yml", false);
        }

        YamlConfiguration chosen;
        if (file.exists()) {
            chosen = YamlConfiguration.loadConfiguration(file);
        } else {
            plugin.getLogger().warning("No language file for '" + language + "', using English");
            chosen = english;
        }
        chosen.setDefaults(english);
        this.lang = chosen;
    }

    /** The message with the configured prefix in front. */
    public Component get(String key, Map<String, String> values) {
        return MINI.deserialize(lang.getString("prefix", "") + lang.getString(key, key), resolvers(values));
    }

    public Component get(String key) {
        return get(key, Map.of());
    }

    /** The message without the prefix, for continuation lines. */
    public Component line(String key, Map<String, String> values) {
        return MINI.deserialize(lang.getString(key, key), resolvers(values));
    }

    private static TagResolver[] resolvers(Map<String, String> values) {
        return values.entrySet().stream()
                .map(entry -> Placeholder.unparsed(entry.getKey(), entry.getValue()))
                .toArray(TagResolver[]::new);
    }

    private YamlConfiguration bundled(String language) {
        try (Reader reader = new InputStreamReader(
                plugin.getResource("lang/" + language + ".yml"), StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        } catch (IOException | NullPointerException e) {
            throw new IllegalStateException("Bundled language file lang/" + language + ".yml is missing", e);
        }
    }
}
