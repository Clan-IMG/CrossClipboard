package net.clanimg.crossClipboard;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reads the plugin.yml as Gradle expanded it. A description with a non-ASCII character once made the build
 * emit a file Paper refuses to load ("special characters are not allowed"), which no other test would notice.
 */
class PluginDescriptorTest {

    private static YamlConfiguration expandedPluginYml() throws IOException, CharacterCodingException {
        byte[] raw;
        try (InputStream in = PluginDescriptorTest.class.getResourceAsStream("/plugin.yml")) {
            raw = in.readAllBytes();
        }
        String text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(raw))
                .toString();
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(text);
        } catch (org.bukkit.configuration.InvalidConfigurationException e) {
            throw new AssertionError("plugin.yml does not parse: " + e.getMessage(), e);
        }
        return yaml;
    }

    @Test
    void isValidUtf8AndParses() throws Exception {
        YamlConfiguration yaml = expandedPluginYml();

        assertEquals("CrossClipboard", yaml.getString("name"));
        assertEquals("net.clanimg.crossClipboard.CrossClipboard", yaml.getString("main"));
    }

    @Test
    void hasNoUnexpandedPlaceholders() throws Exception {
        YamlConfiguration yaml = expandedPluginYml();

        assertFalse(yaml.getString("version").contains("$"), "version was not expanded");
        assertFalse(yaml.getString("description").contains("$"), "description was not expanded");
    }

    @Test
    void descriptionStaysAsciiBecauseGradleReadsPropertiesAsLatin1() throws Exception {
        String description = expandedPluginYml().getString("description");

        assertTrue(description.chars().allMatch(c -> c >= 0x20 && c < 0x7F),
                "non-ASCII in the description (gradle.properties is read as ISO-8859-1): " + description);
    }

    @Test
    void mainClassExists() throws Exception {
        String main = expandedPluginYml().getString("main");

        assertNotNull(Class.forName(main, false, getClass().getClassLoader()));
    }

    @Test
    void declaresTheCommandAndPermissionsTheCodeChecks() throws Exception {
        YamlConfiguration yaml = expandedPluginYml();

        assertTrue(yaml.isConfigurationSection("commands.crossclipboard"));
        assertTrue(yaml.isConfigurationSection("permissions.crossclipboard.use"));
        assertTrue(yaml.isConfigurationSection("permissions.crossclipboard.admin"));
        assertTrue(yaml.getStringList("softdepend").contains("FastAsyncWorldEdit"));
        assertTrue(yaml.getStringList("softdepend").contains("WorldEdit"));
    }
}
