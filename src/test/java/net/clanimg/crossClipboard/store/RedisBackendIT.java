package net.clanimg.crossClipboard.store;

import net.clanimg.crossClipboard.Settings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import redis.clients.jedis.Jedis;

import java.time.Duration;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Talks to a real Redis. Opt in with {@code CROSSCLIPBOARD_TEST_REDIS=host:port} pointing at a throwaway
 * instance; every key uses a random prefix and is removed afterwards, but nothing else is protected.
 */
@EnabledIfEnvironmentVariable(named = "CROSSCLIPBOARD_TEST_REDIS", matches = ".+:\\d+")
class RedisBackendIT {

    private final String prefix = "cc-test-" + UUID.randomUUID();
    private final UUID player = UUID.randomUUID();
    private RedisBackend backend;
    private String host;
    private int port;

    @BeforeEach
    void connect() {
        String[] address = System.getenv("CROSSCLIPBOARD_TEST_REDIS").split(":");
        host = address[0];
        port = Integer.parseInt(address[1]);
        backend = new RedisBackend(new Settings.Redis(host, port, "", "", 0, false, 3000, 4), prefix,
                Logger.getLogger("test"));
    }

    @AfterEach
    void cleanUp() {
        try (Jedis jedis = new Jedis(host, port)) {
            jedis.keys(prefix + ":*").forEach(jedis::del);
        }
        backend.close();
    }

    private static StoredClipboard clipboard(byte[] data, double[] transform) {
        return new StoredClipboard(new StoredClipboard.Meta(
                42L, 1_700_000_000_000L, "lobby", "FAST_V3", 1234, "10x20x30", data.length, transform, -1), data);
    }

    @Test
    void pingSucceeds() {
        backend.ping();
    }

    @Test
    void roundTripsBinaryDataAndMetadata() {
        byte[] data = new byte[300];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) i;
        }
        double[] transform = {0, 0, 1, 0, 0, 1, 0, 0, -1, 0, 0, 0.5};

        backend.save(player, clipboard(data, transform), Duration.ofHours(1));

        StoredClipboard loaded = backend.load(player).orElseThrow();
        assertArrayEquals(data, loaded.data());
        assertEquals(42L, loaded.meta().version());
        assertEquals(1_700_000_000_000L, loaded.meta().updatedMillis());
        assertEquals("lobby", loaded.meta().server());
        assertEquals("FAST_V3", loaded.meta().format());
        assertEquals(1234, loaded.meta().blocks());
        assertEquals("10x20x30", loaded.meta().size());
        assertEquals(300, loaded.meta().bytes());
        assertArrayEquals(transform, loaded.meta().transform());
        assertTrue(loaded.meta().ttlSeconds() > 3500 && loaded.meta().ttlSeconds() <= 3600,
                "ttl was " + loaded.meta().ttlSeconds());
    }

    @Test
    void roundTripsAMultiMegabyteClipboard() {
        byte[] data = new byte[20 * 1024 * 1024];
        new Random(7).nextBytes(data);

        backend.save(player, clipboard(data, null), Duration.ofMinutes(5));

        assertArrayEquals(data, backend.load(player).orElseThrow().data());
    }

    @Test
    void metaDoesNotNeedThePayload() {
        backend.save(player, clipboard(new byte[]{1, 2, 3}, null), Duration.ofMinutes(5));

        StoredClipboard.Meta meta = backend.meta(player).orElseThrow();

        assertEquals("lobby", meta.server());
        assertNull(meta.transform());
    }

    @Test
    void savingReplacesTheWholeEntry() {
        backend.save(player, clipboard(new byte[]{1}, new double[]{1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0}),
                Duration.ofMinutes(5));
        backend.save(player, clipboard(new byte[]{2}, null), Duration.ofMinutes(5));

        StoredClipboard loaded = backend.load(player).orElseThrow();
        assertArrayEquals(new byte[]{2}, loaded.data());
        assertNull(loaded.meta().transform(), "the earlier transform must not survive");
    }

    @Test
    void anUnknownPlayerHasNothingStored() {
        assertEquals(Optional.empty(), backend.load(UUID.randomUUID()));
        assertEquals(Optional.empty(), backend.meta(UUID.randomUUID()));
    }

    @Test
    void deleteRemovesTheEntry() {
        backend.save(player, clipboard(new byte[]{1}, null), Duration.ofMinutes(5));

        backend.delete(player);

        assertEquals(Optional.empty(), backend.load(player));
    }

    @Test
    void claimReturnsThePreviousHolder() {
        assertNull(backend.claim(player, "A"));
        assertEquals("A", backend.claim(player, "B"));
        assertEquals("B", backend.claim(player, "B"));
    }

    @Test
    void releaseOnlyRemovesTheCallersOwnClaim() {
        backend.claim(player, "A");
        backend.claim(player, "B");

        assertFalse(backend.release(player, "A"), "B already took over");
        assertEquals("B", backend.claim(player, "B"), "B's claim survived");
        assertTrue(backend.release(player, "B"));
        assertNull(backend.claim(player, "C"), "released, so nobody held the player");
    }

    @Test
    void presenceHasAnExpiry() {
        backend.claim(player, "A");

        try (Jedis jedis = new Jedis(host, port)) {
            long ttl = jedis.ttl(prefix + ":presence:" + player);
            assertTrue(ttl > 0 && ttl <= 86_400, "ttl was " + ttl);
        }
    }

    @Test
    void releaseAnnouncementsReachEverySubscriber() throws Exception {
        BlockingQueue<String> first = new LinkedBlockingQueue<>();
        BlockingQueue<String> second = new LinkedBlockingQueue<>();
        try (AutoCloseable one = backend.subscribe((id, from) -> first.add(id + "@" + from));
             AutoCloseable two = backend.subscribe((id, from) -> second.add(id + "@" + from))) {
            awaitSubscribers(2);

            backend.publishReleased(player, "A");

            assertEquals(player + "@A", first.poll(3, TimeUnit.SECONDS));
            assertEquals(player + "@A", second.poll(3, TimeUnit.SECONDS));
        }
    }

    @Test
    void aSecondBackendInstanceHearsTheFirst() throws Exception {
        RedisBackend other = new RedisBackend(new Settings.Redis(host, port, "", "", 0, false, 3000, 2), prefix,
                Logger.getLogger("test"));
        BlockingQueue<String> heard = new LinkedBlockingQueue<>();
        try (AutoCloseable subscription = other.subscribe((id, from) -> heard.add(id + "@" + from))) {
            awaitSubscribers(1);

            backend.publishReleased(player, "server-1");

            assertEquals(player + "@server-1", heard.poll(3, TimeUnit.SECONDS));
        } finally {
            other.close();
        }
    }

    @Test
    void theSubscriptionRecoversWhenRedisDropsTheConnection() throws Exception {
        BlockingQueue<String> heard = new LinkedBlockingQueue<>();
        try (AutoCloseable subscription = backend.subscribe((id, from) -> heard.add(id + "@" + from))) {
            awaitSubscribers(1);

            try (Jedis admin = new Jedis(host, port)) {
                admin.clientKill(new redis.clients.jedis.params.ClientKillParams()
                        .type(redis.clients.jedis.args.ClientType.PUBSUB));
            }

            // The subscriber pauses two seconds before reconnecting; keep announcing until it is back.
            String expected = player + "@A";
            String received = null;
            for (int attempt = 0; attempt < 20 && received == null; attempt++) {
                backend.publishReleased(player, "A");
                received = heard.poll(500, TimeUnit.MILLISECONDS);
            }
            assertNotNull(received, "no announcement arrived after the connection was killed");
            assertEquals(expected, received);
        }
    }

    /** Pub/sub delivers only to clients already subscribed, so tests wait for the server to list them. */
    private void awaitSubscribers(int expected) throws InterruptedException {
        String channel = prefix + ":events";
        try (Jedis jedis = new Jedis(host, port)) {
            long deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline) {
                if (jedis.pubsubNumSub(channel).getOrDefault(channel, 0L) >= expected) {
                    return;
                }
                Thread.sleep(50);
            }
        }
        throw new AssertionError("subscribers never registered");
    }
}
