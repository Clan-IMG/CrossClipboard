package net.clanimg.crossClipboard;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LanguageFilesTest {

    private static YamlConfiguration lang(String code) throws IOException {
        try (Reader reader = new InputStreamReader(
                LanguageFilesTest.class.getResourceAsStream("/lang/" + code + ".yml"), StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        }
    }

    @Test
    void germanHasExactlyTheKeysEnglishHas() throws IOException {
        assertEquals(lang("en").getKeys(false), lang("de").getKeys(false));
    }

    @Test
    void everyPlayerFacingMessageOfTheCodeExists() throws IOException {
        Set<String> keys = lang("en").getKeys(false);
        for (String used : new String[]{
                "restored", "pushed", "nothing-to-push", "nothing-stored", "too-large", "cleared",
                "status-local", "status-no-local", "status-stored", "status-none",
                "store-error", "process-error", "usage", "usage-admin", "players-only", "no-permission",
                "reloaded", "restart-required"}) {
            assertTrue(keys.contains(used), "lang/en.yml lacks '" + used + "'");
        }
    }

    @Test
    void placeholdersMatchBetweenLanguages() throws IOException {
        YamlConfiguration english = lang("en");
        YamlConfiguration german = lang("de");
        for (String key : english.getKeys(false)) {
            assertEquals(placeholders(english.getString(key)), placeholders(german.getString(key)),
                    "placeholders differ for '" + key + "'");
        }
    }

    private static Set<String> placeholders(String text) {
        return java.util.regex.Pattern.compile("<(server|blocks|size|bytes|limit|age|expires|reason|command)>")
                .matcher(text)
                .results()
                .map(match -> match.group(1))
                .collect(java.util.stream.Collectors.toSet());
    }
}
