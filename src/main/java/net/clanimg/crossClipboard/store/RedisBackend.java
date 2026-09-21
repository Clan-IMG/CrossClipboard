package net.clanimg.crossClipboard.store;

import net.clanimg.crossClipboard.Settings;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisClientConfig;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.JedisPubSub;
import redis.clients.jedis.Transaction;
import redis.clients.jedis.exceptions.JedisException;

import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Redis implementation of both the clipboard store and the handoff channel.
 *
 * <p>Layout, with {@code p} the configured key prefix:
 * <ul>
 *   <li>{@code p:data:<uuid>}: hash with the serialized clipboard and its metadata, expires after the TTL</li>
 *   <li>{@code p:presence:<uuid>}: the instance that currently hosts the player</li>
 *   <li>{@code p:events}: pub/sub channel announcing that an instance finished uploading a player</li>
 * </ul>
 */
public final class RedisBackend implements ClipboardStore, HandoffChannel, AutoCloseable {

    private static final String CLAIM_SCRIPT =
            "local previous = redis.call('GET', KEYS[1]) "
                    + "redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2]) "
                    + "return previous";
    private static final String RELEASE_SCRIPT =
            "if redis.call('GET', KEYS[1]) == ARGV[1] then redis.call('DEL', KEYS[1]) return 1 end return 0";

    /** A player is never on one server for longer than this without a rejoin refreshing the record. */
    private static final long PRESENCE_TTL_SECONDS = Duration.ofDays(1).toSeconds();
    /** Subscribers reconnect after this much silence; it also bounds how long a dead connection goes unnoticed. */
    private static final int SUBSCRIBER_TIMEOUT_MILLIS = 30_000;

    private static final byte[] F_DATA = utf8("data");
    private static final byte[] F_VERSION = utf8("version");
    private static final byte[] F_UPDATED = utf8("updated");
    private static final byte[] F_SERVER = utf8("server");
    private static final byte[] F_FORMAT = utf8("format");
    private static final byte[] F_BLOCKS = utf8("blocks");
    private static final byte[] F_SIZE = utf8("size");
    private static final byte[] F_BYTES = utf8("bytes");
    private static final byte[] F_TRANSFORM = utf8("transform");

    private final JedisPool pool;
    private final HostAndPort address;
    private final Settings.Redis config;
    private final String dataPrefix;
    private final String presencePrefix;
    private final String channel;
    private final Logger log;

    public RedisBackend(Settings.Redis config, String keyPrefix, Logger log) {
        this.config = config;
        this.log = log;
        this.dataPrefix = keyPrefix + ":data:";
        this.presencePrefix = keyPrefix + ":presence:";
        this.channel = keyPrefix + ":events";
        this.address = new HostAndPort(config.host(), config.port());

        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(config.poolSize());
        poolConfig.setMaxIdle(config.poolSize());
        poolConfig.setMinIdle(1);
        poolConfig.setTestOnBorrow(true);
        this.pool = new JedisPool(poolConfig, address, clientConfig(config.timeoutMillis()));
    }

    private JedisClientConfig clientConfig(int socketTimeoutMillis) {
        DefaultJedisClientConfig.Builder builder = DefaultJedisClientConfig.builder()
                .connectionTimeoutMillis(config.timeoutMillis())
                .socketTimeoutMillis(socketTimeoutMillis)
                .database(config.database())
                .ssl(config.ssl());
        if (!config.username().isBlank()) {
            builder.user(config.username());
        }
        if (!config.password().isBlank()) {
            builder.password(config.password());
        }
        return builder.build();
    }

    /** Throws {@link StoreException} if Redis is unreachable or rejects the credentials. */
    public void ping() {
        try (Jedis jedis = pool.getResource()) {
            jedis.ping();
        } catch (JedisException e) {
            throw new StoreException("Cannot reach Redis at " + address, e);
        }
    }

    // ---- ClipboardStore ----

    @Override
    public void save(UUID owner, StoredClipboard clipboard, Duration ttl) {
        byte[] key = dataKey(owner);
        StoredClipboard.Meta meta = clipboard.meta();

        Map<byte[], byte[]> fields = new HashMap<>();
        fields.put(F_DATA, clipboard.data());
        fields.put(F_VERSION, utf8(Long.toString(meta.version())));
        fields.put(F_UPDATED, utf8(Long.toString(meta.updatedMillis())));
        fields.put(F_SERVER, utf8(meta.server()));
        fields.put(F_FORMAT, utf8(meta.format()));
        fields.put(F_BLOCKS, utf8(Long.toString(meta.blocks())));
        fields.put(F_SIZE, utf8(meta.size()));
        fields.put(F_BYTES, utf8(Long.toString(meta.bytes())));
        if (meta.transform() != null) {
            fields.put(F_TRANSFORM, utf8(encodeTransform(meta.transform())));
        }

        try (Jedis jedis = pool.getResource(); Transaction tx = jedis.multi()) {
            // Replace rather than merge, so a clipboard without a transform never inherits an old one.
            tx.del(key);
            tx.hset(key, fields);
            tx.expire(key, ttl.toSeconds());
            tx.exec();
        } catch (JedisException e) {
            throw new StoreException("Could not save clipboard of " + owner, e);
        }
    }

    @Override
    public Optional<StoredClipboard> load(UUID owner) {
        byte[] key = dataKey(owner);
        try (Jedis jedis = pool.getResource()) {
            List<byte[]> values = jedis.hmget(key, F_DATA, F_VERSION, F_UPDATED, F_SERVER, F_FORMAT, F_BLOCKS,
                    F_SIZE, F_BYTES, F_TRANSFORM);
            byte[] data = values.get(0);
            if (data == null) {
                return Optional.empty();
            }
            StoredClipboard.Meta meta = parseMeta(values.subList(1, values.size()), jedis.ttl(key));
            return meta == null ? Optional.empty() : Optional.of(new StoredClipboard(meta, data));
        } catch (JedisException | NumberFormatException e) {
            throw new StoreException("Could not load clipboard of " + owner, e);
        }
    }

    @Override
    public Optional<StoredClipboard.Meta> meta(UUID owner) {
        byte[] key = dataKey(owner);
        try (Jedis jedis = pool.getResource()) {
            List<byte[]> values = jedis.hmget(key, F_VERSION, F_UPDATED, F_SERVER, F_FORMAT, F_BLOCKS, F_SIZE,
                    F_BYTES, F_TRANSFORM);
            return Optional.ofNullable(parseMeta(values, jedis.ttl(key)));
        } catch (JedisException | NumberFormatException e) {
            throw new StoreException("Could not read clipboard metadata of " + owner, e);
        }
    }

    @Override
    public void delete(UUID owner) {
        try (Jedis jedis = pool.getResource()) {
            jedis.del(dataKey(owner));
        } catch (JedisException e) {
            throw new StoreException("Could not delete clipboard of " + owner, e);
        }
    }

    /** {@code values} in the order version, updated, server, format, blocks, size, bytes, transform. */
    private static StoredClipboard.Meta parseMeta(List<byte[]> values, long ttlSeconds) {
        if (values.get(0) == null) {
            return null;
        }
        String transform = text(values.get(7));
        return new StoredClipboard.Meta(
                Long.parseLong(text(values.get(0))),
                Long.parseLong(text(values.get(1))),
                text(values.get(2)),
                text(values.get(3)),
                Long.parseLong(text(values.get(4))),
                text(values.get(5)),
                Long.parseLong(text(values.get(6))),
                transform == null ? null : decodeTransform(transform),
                ttlSeconds
        );
    }

    // ---- HandoffChannel ----

    @Override
    public String claim(UUID player, String instanceId) {
        try (Jedis jedis = pool.getResource()) {
            Object previous = jedis.eval(CLAIM_SCRIPT, List.of(presenceKey(player)),
                    List.of(instanceId, Long.toString(PRESENCE_TTL_SECONDS)));
            return previous instanceof byte[] raw ? new String(raw, StandardCharsets.UTF_8)
                    : previous == null ? null : previous.toString();
        } catch (JedisException e) {
            throw new StoreException("Could not claim " + player, e);
        }
    }

    @Override
    public boolean release(UUID player, String instanceId) {
        try (Jedis jedis = pool.getResource()) {
            Object removed = jedis.eval(RELEASE_SCRIPT, List.of(presenceKey(player)), List.of(instanceId));
            return removed instanceof Long count && count == 1L;
        } catch (JedisException e) {
            throw new StoreException("Could not release " + player, e);
        }
    }

    @Override
    public void publishReleased(UUID player, String instanceId) {
        try (Jedis jedis = pool.getResource()) {
            jedis.publish(channel, player + "|" + instanceId);
        } catch (JedisException e) {
            throw new StoreException("Could not announce release of " + player, e);
        }
    }

    @Override
    public AutoCloseable subscribe(BiConsumer<UUID, String> listener) {
        Subscriber subscriber = new Subscriber(listener);
        subscriber.start();
        return subscriber;
    }

    @Override
    public void close() {
        pool.close();
    }

    /**
     * Owns a dedicated connection (pub/sub monopolises it) and keeps re-subscribing until closed. A release
     * missed while reconnecting is harmless: the joining server falls back to its handoff timeout.
     */
    private final class Subscriber extends JedisPubSub implements AutoCloseable {

        private final BiConsumer<UUID, String> listener;
        private final Thread thread = new Thread(this::run, "CrossClipboard-Subscriber");
        private volatile boolean running = true;
        private volatile Jedis connection;

        Subscriber(BiConsumer<UUID, String> listener) {
            this.listener = listener;
            thread.setDaemon(true);
        }

        void start() {
            thread.start();
        }

        private void run() {
            while (running) {
                try (Jedis jedis = new Jedis(address, clientConfig(SUBSCRIBER_TIMEOUT_MILLIS))) {
                    connection = jedis;
                    jedis.subscribe(this, channel);
                } catch (Exception e) {
                    if (running && !isIdleTimeout(e)) {
                        log.log(Level.WARNING, "Redis subscription lost, retrying in 2s: " + e.getMessage());
                        pause(2000);
                    }
                } finally {
                    connection = null;
                }
            }
        }

        @Override
        public void onMessage(String ignored, String message) {
            int bar = message.indexOf('|');
            if (bar < 0) {
                return;
            }
            try {
                listener.accept(UUID.fromString(message.substring(0, bar)), message.substring(bar + 1));
            } catch (RuntimeException e) {
                log.log(Level.WARNING, "Ignoring malformed handoff message: " + message, e);
            }
        }

        private static boolean isIdleTimeout(Throwable e) {
            for (Throwable cause = e; cause != null; cause = cause.getCause()) {
                if (cause instanceof SocketTimeoutException) {
                    return true;
                }
            }
            return false;
        }

        private static void pause(long millis) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void close() {
            running = false;
            Jedis current = connection;
            if (current != null) {
                current.close();
            }
            thread.interrupt();
        }
    }

    // ---- encoding helpers ----

    private byte[] dataKey(UUID owner) {
        return utf8(dataPrefix + owner);
    }

    private String presenceKey(UUID owner) {
        return presencePrefix + owner;
    }

    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String text(byte[] value) {
        return value == null ? null : new String(value, StandardCharsets.UTF_8);
    }

    private static String encodeTransform(double[] transform) {
        return Arrays.stream(transform).mapToObj(Double::toString).collect(Collectors.joining(","));
    }

    private static double[] decodeTransform(String encoded) {
        return Arrays.stream(encoded.split(",")).mapToDouble(Double::parseDouble).toArray();
    }
}
