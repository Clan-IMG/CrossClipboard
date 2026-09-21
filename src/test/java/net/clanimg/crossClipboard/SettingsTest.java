package net.clanimg.crossClipboard;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsTest {

    private static YamlConfiguration bundled(String resource) throws IOException {
        try (Reader reader = new InputStreamReader(
                SettingsTest.class.getResourceAsStream("/" + resource), StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        }
    }

    @Test
    void shippedConfigUsesTheRecommendedLimits() throws IOException {
        Settings settings = Settings.load(bundled("config.yml"));

        assertEquals(50L * 1024 * 1024, settings.maxBytes());
        assertEquals(Duration.ofHours(24), settings.ttl());
        assertEquals(Duration.ofSeconds(5), settings.handoffTimeout());
        assertEquals("auto", settings.format());
        assertTrue(SettingsTest.class.getResource("/lang/" + settings.language() + ".yml") != null,
                "the shipped default language '" + settings.language() + "' has no bundled language file");
        assertTrue(settings.notifyOnRestore());
    }

    @Test
    void anEmptyConfigStillYieldsUsableSettings() {
        Settings settings = Settings.load(new YamlConfiguration());

        assertEquals("127.0.0.1", settings.redis().host());
        assertEquals(6379, settings.redis().port());
        assertEquals("crossclipboard", settings.keyPrefix());
        assertEquals(Duration.ofHours(24), settings.ttl());
    }

    @Test
    void outOfRangeValuesAreClamped() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("sync.max-size-mb", 100_000);
        config.set("sync.ttl-hours", 0);
        config.set("sync.handoff-timeout-seconds", 9999);
        config.set("redis.pool-size", 1);
        config.set("redis.timeout-ms", 1);

        Settings settings = Settings.load(config);

        assertEquals(400L * 1024 * 1024, settings.maxBytes(), "Redis caps values at 512 MB");
        assertEquals(Duration.ofHours(1), settings.ttl());
        assertEquals(Duration.ofSeconds(60), settings.handoffTimeout());
        assertEquals(2, settings.redis().poolSize());
        assertEquals(500, settings.redis().timeoutMillis());
    }

    @Test
    void aRedisUrlOverridesTheSeparateFields() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("redis.host", "ignored");
        config.set("redis.url", "redis://default:s3cret@cache.internal:6310/2");

        Settings.Redis redis = Settings.load(config).redis();

        assertEquals("cache.internal", redis.host());
        assertEquals(6310, redis.port());
        assertEquals("default", redis.username());
        assertEquals("s3cret", redis.password());
        assertEquals(2, redis.database());
        assertFalse(redis.ssl());
    }

    @Test
    void parsesTheUrlShapesCoolifyAndOthersProduce() {
        Settings.Redis passwordOnly = Settings.fromUrl("redis://:pw@host", 3000, 6);
        assertEquals("", passwordOnly.username());
        assertEquals("pw", passwordOnly.password());
        assertEquals(6379, passwordOnly.port());
        assertEquals(0, passwordOnly.database());

        Settings.Redis tls = Settings.fromUrl("rediss://user:pw@secure.example:6380", 3000, 6);
        assertTrue(tls.ssl());
        assertEquals("user", tls.username());

        Settings.Redis bare = Settings.fromUrl("redis://localhost", 3000, 6);
        assertEquals("", bare.password());
    }

    @Test
    void rejectsUrlsThatAreNotRedis() {
        assertThrows(IllegalArgumentException.class, () -> Settings.fromUrl("http://host:6379", 3000, 6));
        assertThrows(IllegalArgumentException.class, () -> Settings.fromUrl("localhost:6379", 3000, 6));
        assertThrows(IllegalArgumentException.class, () -> Settings.fromUrl("redis:///0", 3000, 6));
    }
}
