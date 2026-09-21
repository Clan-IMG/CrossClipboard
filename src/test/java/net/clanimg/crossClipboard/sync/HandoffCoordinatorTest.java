package net.clanimg.crossClipboard.sync;

import net.clanimg.crossClipboard.store.HandoffChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plays out server switches with several coordinators sharing one in-memory channel. Tasks run on the calling
 * thread, so the recorded event order is exactly the order in which the coordinators decided to act.
 */
class HandoffCoordinatorTest {

    private static final UUID P1 = new UUID(0, 1);
    private static final UUID P2 = new UUID(0, 2);

    private final List<String> events = Collections.synchronizedList(new ArrayList<>());
    private final FakeChannel channel = new FakeChannel();
    private final List<HandoffCoordinator> coordinators = new ArrayList<>();
    private ScheduledExecutorService scheduler;

    @BeforeEach
    void setUp() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
    }

    @AfterEach
    void tearDown() {
        coordinators.forEach(HandoffCoordinator::close);
        scheduler.shutdownNow();
    }

    /** One backend server. */
    private final class Node {

        private final String name;
        private final HandoffCoordinator coordinator;

        Node(String name, Duration timeout) {
            this.name = name;
            this.coordinator = new HandoffCoordinator(channel, name, () -> timeout, scheduler, Runnable::run,
                    Logger.getLogger("test"));
            coordinators.add(coordinator);
        }

        void join(UUID player) {
            coordinator.onJoin(player, () -> events.add(name + ".load:" + label(player)));
        }

        void quit(UUID player) {
            coordinator.onQuit(player, () -> events.add(name + ".flush:" + label(player)));
        }

        void quitNow(UUID player) {
            coordinator.onQuitNow(player, () -> events.add(name + ".flush:" + label(player)));
        }
    }

    private Node node(String name) {
        return new Node(name, Duration.ofSeconds(30));
    }

    private static String label(UUID player) {
        return "p" + player.getLeastSignificantBits();
    }

    @Test
    void joinLoadsImmediatelyWhenNobodyElseHasThePlayer() {
        node("A").join(P1);

        assertEquals(List.of("A.load:p1"), events);
    }

    @Test
    void joinWaitsUntilThePreviousServerHasUploaded() {
        Node a = node("A");
        Node b = node("B");
        a.join(P1);

        // Velocity connects the player to B before A notices they left.
        b.join(P1);
        assertEquals(List.of("A.load:p1"), events, "B must not load before A has uploaded");

        a.quit(P1);
        assertEquals(List.of("A.load:p1", "A.flush:p1", "B.load:p1"), events);
    }

    @Test
    void joinDoesNotWaitWhenThePreviousServerAlreadyFinished() {
        Node a = node("A");
        Node b = node("B");
        a.join(P1);
        a.quit(P1);

        b.join(P1);

        assertEquals(List.of("A.load:p1", "A.flush:p1", "B.load:p1"), events);
    }

    @Test
    void joinLoadsAfterTheTimeoutWhenThePreviousServerNeverAnswers() throws InterruptedException {
        Node a = new Node("A", Duration.ofMillis(150));
        Node b = new Node("B", Duration.ofMillis(150));
        a.join(P1);
        b.join(P1);

        assertEquals(List.of("A.load:p1"), events, "must wait for A at first");

        awaitEvent("B.load:p1");
    }

    @Test
    void aReleaseFromAnUnrelatedServerDoesNotEndTheWait() {
        Node a = node("A");
        Node b = node("B");
        Node c = node("C");
        a.join(P1);
        b.join(P1);

        // C says it is done with P1 (they passed through it earlier). B is waiting for A, not C.
        c.quit(P1);
        assertEquals(List.of("A.load:p1", "C.flush:p1"), events);

        a.quit(P1);
        assertEquals(List.of("A.load:p1", "C.flush:p1", "A.flush:p1", "B.load:p1"), events);
    }

    @Test
    void aReleaseThatArrivesBeforeTheClaimReturnsIsNotLost() {
        Node a = node("A");
        Node b = node("B");
        a.join(P1);

        // A finishes and announces at the very moment B's claim is answered.
        channel.beforeClaimReturns = () -> channel.deliver(P1, "A");
        b.join(P1);

        assertEquals(List.of("A.load:p1", "B.load:p1"), events);
    }

    @Test
    void quickHopsKeepEveryUploadBeforeTheNextLoad() {
        Node a = node("A");
        Node b = node("B");
        Node c = node("C");
        a.join(P1);

        b.join(P1);   // waits for A
        b.quit(P1);   // player moves on before B ever loaded
        c.join(P1);   // waits for B

        a.quit(P1);

        assertEquals(List.of("A.load:p1", "A.flush:p1", "B.flush:p1", "C.load:p1"), events,
                "B never loads for a player who left, but C still waits for B's release");
    }

    @Test
    void playersDoNotWaitOnEachOther() {
        Node a = node("A");
        Node b = node("B");
        a.join(P1);
        a.join(P2);
        b.join(P1);
        b.join(P2);
        events.clear();

        a.quit(P1);

        assertEquals(List.of("A.flush:p1", "B.load:p1"), events, "P2 is still waiting for A");

        a.quit(P2);
        assertEquals(List.of("A.flush:p1", "B.load:p1", "A.flush:p2", "B.load:p2"), events);
    }

    @Test
    void loadRunsOnceEvenWhenTheReleaseAndTheTimeoutBothArrive() throws InterruptedException {
        Node a = new Node("A", Duration.ofMillis(100));
        Node b = new Node("B", Duration.ofMillis(100));
        a.join(P1);
        b.join(P1);
        a.quit(P1);

        Thread.sleep(300);

        assertEquals(1, events.stream().filter("B.load:p1"::equals).count());
    }

    @Test
    void shutdownUploadsWithoutWaitingAndAbandonsTheLoad() {
        Node a = node("A");
        Node b = node("B");
        a.join(P1);
        b.join(P1);

        b.quitNow(P1);
        a.quit(P1);

        assertEquals(List.of("A.load:p1", "B.flush:p1", "A.flush:p1"), events, "B's load was abandoned");
    }

    @Test
    void aFailingUploadStillReleasesThePlayer() {
        Node a = node("A");
        Node b = node("B");
        a.join(P1);
        b.join(P1);

        a.coordinator.onQuit(P1, () -> {
            throw new IllegalStateException("boom");
        });

        assertEquals(List.of("A.load:p1", "B.load:p1"), events);
    }

    @Test
    void joinDoesNotTalkToTheChannelOnTheCallersThread() throws Exception {
        // onJoin is called from the server thread; a slow Redis must not be able to stall it.
        Thread caller = Thread.currentThread();
        AtomicReference<Thread> claimedOn = new AtomicReference<>();
        channel.beforeClaimReturns = () -> claimedOn.set(Thread.currentThread());
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            HandoffCoordinator coordinator = new HandoffCoordinator(channel, "A", () -> Duration.ofSeconds(30),
                    scheduler, worker, Logger.getLogger("test"));
            coordinators.add(coordinator);
            CountDownLatch loaded = new CountDownLatch(1);

            coordinator.onJoin(P1, loaded::countDown);

            assertTrue(loaded.await(3, TimeUnit.SECONDS), "the load never ran");
            assertNotSame(caller, claimedOn.get());
        } finally {
            worker.shutdownNow();
        }
    }

    private void awaitEvent(String event) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000;
        while (!events.contains(event) && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(events.contains(event), "expected " + event + " but saw " + events);
    }

    /** What Redis provides, held in memory: one presence record per player and a broadcast of releases. */
    private static final class FakeChannel implements HandoffChannel {

        private final Map<UUID, String> presence = new HashMap<>();
        private final List<BiConsumer<UUID, String>> listeners = new CopyOnWriteArrayList<>();
        private Runnable beforeClaimReturns = () -> {
        };

        @Override
        public synchronized String claim(UUID player, String instanceId) {
            String previous = presence.put(player, instanceId);
            Runnable hook = beforeClaimReturns;
            beforeClaimReturns = () -> {
            };
            hook.run();
            return previous;
        }

        @Override
        public synchronized boolean release(UUID player, String instanceId) {
            return presence.remove(player, instanceId);
        }

        @Override
        public void publishReleased(UUID player, String instanceId) {
            deliver(player, instanceId);
        }

        void deliver(UUID player, String from) {
            listeners.forEach(listener -> listener.accept(player, from));
        }

        @Override
        public AutoCloseable subscribe(BiConsumer<UUID, String> listener) {
            listeners.add(listener);
            return () -> listeners.remove(listener);
        }
    }
}
