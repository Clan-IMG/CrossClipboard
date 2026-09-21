package net.clanimg.crossClipboard;

import org.bukkit.configuration.ConfigurationSection;

import java.net.URI;
import java.time.Duration;

/** Immutable snapshot of config.yml. Out-of-range values are clamped instead of rejected. */
public record Settings(
        Redis redis,
        String keyPrefix,
        String format,
        long maxBytes,
        Duration ttl,
        Duration handoffTimeout,
        String serverName,
        boolean notifyOnRestore,
        String language
) {

    private static final long MEGABYTE = 1024L * 1024L;
    /** Redis refuses values above 512 MB, so stay clearly below that. */
    private static final int MAX_SIZE_MB = 400;

    public record Redis(
            String host,
            int port,
            String username,
            String password,
            int database,
            boolean ssl,
            int timeoutMillis,
            int poolSize
    ) {
    }

    public static Settings load(ConfigurationSection config) {
        ConfigurationSection redis = config.getConfigurationSection("redis");
        if (redis == null) {
            redis = config.createSection("redis");
        }
        ConfigurationSection sync = config.getConfigurationSection("sync");
        if (sync == null) {
            sync = config.createSection("sync");
        }

        Redis connection = redis(redis);
        int sizeMb = Math.clamp(sync.getInt("max-size-mb", 50), 1, MAX_SIZE_MB);
        int ttlHours = Math.max(1, sync.getInt("ttl-hours", 24));
        int handoffSeconds = Math.clamp(sync.getInt("handoff-timeout-seconds", 5), 1, 60);

        return new Settings(
                connection,
                redis.getString("key-prefix", "crossclipboard"),
                sync.getString("format", "auto").trim(),
                sizeMb * MEGABYTE,
                Duration.ofHours(ttlHours),
                Duration.ofSeconds(handoffSeconds),
                config.getString("server-name", "").trim(),
                config.getBoolean("notify-on-restore", true),
                config.getString("language", "en").trim().toLowerCase()
        );
    }

    private static Redis redis(ConfigurationSection section) {
        int timeout = Math.max(500, section.getInt("timeout-ms", 3000));
        int pool = Math.clamp(section.getInt("pool-size", 6), 2, 64);
        String url = section.getString("url", "").trim();
        if (!url.isEmpty()) {
            return fromUrl(url, timeout, pool);
        }
        return new Redis(
                section.getString("host", "127.0.0.1"),
                section.getInt("port", 6379),
                section.getString("username", ""),
                section.getString("password", ""),
                Math.max(0, section.getInt("database", 0)),
                section.getBoolean("ssl", false),
                timeout,
                pool
        );
    }

    /** Accepts {@code redis://[user:password@]host[:port][/db]} and {@code rediss://} for TLS, as Coolify prints it. */
    static Redis fromUrl(String url, int timeout, int pool) {
        URI uri = URI.create(url);
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equals("redis") || scheme.equals("rediss"))) {
            throw new IllegalArgumentException("redis.url must start with redis:// or rediss://");
        }
        if (uri.getHost() == null) {
            throw new IllegalArgumentException("redis.url has no host");
        }

        String username = "";
        String password = "";
        String userInfo = uri.getUserInfo();
        if (userInfo != null) {
            int colon = userInfo.indexOf(':');
            if (colon < 0) {
                password = userInfo;
            } else {
                username = userInfo.substring(0, colon);
                password = userInfo.substring(colon + 1);
            }
        }

        int database = 0;
        String path = uri.getPath();
        if (path != null && path.length() > 1) {
            database = Integer.parseInt(path.substring(1));
        }

        return new Redis(
                uri.getHost(),
                uri.getPort() == -1 ? 6379 : uri.getPort(),
                username,
                password,
                database,
                scheme.equals("rediss"),
                timeout,
                pool
        );
    }
}
