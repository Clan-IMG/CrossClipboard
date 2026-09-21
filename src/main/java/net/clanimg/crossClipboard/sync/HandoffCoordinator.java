package net.clanimg.crossClipboard.sync;

import net.clanimg.crossClipboard.store.HandoffChannel;

import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Decides <em>when</em> a joining player's clipboard may be loaded, so it is never read before the server
 * they came from has finished writing it.
 *
 * <p>Behind a proxy the new server usually sees the player join before the old server sees them quit. So on
 * join we claim the player; if another instance still held them, we wait for that instance's release
 * announcement (or a timeout) before loading. On quit we upload first, then release, which is the
 * announcement the next server waits for. Everything is keyed by player, so players never wait on each other.
 *
 * <p>Tasks are handed to {@code worker}; nothing here runs Redis calls or user code on the caller's thread
 * except {@link #onQuitNow}.
 */
public final class HandoffCoordinator implements AutoCloseable {

    private final HandoffChannel channel;
    private final String instanceId;
    private final Supplier<Duration> timeout;
    private final ScheduledExecutorService scheduler;
    private final Executor worker;
    private final Logger log;
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();
    private final AutoCloseable subscription;

    public HandoffCoordinator(HandoffChannel channel, String instanceId, Supplier<Duration> timeout,
                              ScheduledExecutorService scheduler, Executor worker, Logger log) {
        this.channel = channel;
        this.instanceId = instanceId;
        this.timeout = timeout;
        this.scheduler = scheduler;
        this.worker = worker;
        this.log = log;
        this.subscription = channel.subscribe(this::onReleased);
    }

    /**
     * Runs {@code load} exactly once: right away if no other instance held the player, otherwise as soon as
     * that instance announces it is done, or when the handoff timeout passes without word from it.
     */
    public void onJoin(UUID player, Runnable load) {
        Pending join = new Pending(player, load);
        Pending stale = pending.put(player, join);
        if (stale != null) {
            stale.fire();
        }

        // A network round trip, and this is called from the server thread: a slow or dead Redis must not stall joins.
        worker.execute(() -> {
            String holder = null;
            try {
                holder = channel.claim(player, instanceId);
            } catch (RuntimeException e) {
                log.log(Level.WARNING, "Could not claim " + player + ", loading without waiting for a handoff", e);
            }
            join.awaiting(instanceId.equals(holder) ? null : holder, timeout.get());
        });
    }

    /**
     * Runs {@code flush} (the upload), then releases the player and announces it. If the player left before
     * their own join was resolved, this waits for that first, so a chain of quick server hops keeps the order
     * upload, release, load.
     */
    public void onQuit(UUID player, Runnable flush) {
        Runnable tail = () -> finishQuit(player, flush);
        Pending waiting = pending.get(player);
        if (waiting == null) {
            worker.execute(guarded(tail));
        } else {
            waiting.leaveInstead(tail);
        }
    }

    /** Same as {@link #onQuit} but on the calling thread and without waiting; for server shutdown. */
    public void onQuitNow(UUID player, Runnable flush) {
        Pending waiting = pending.remove(player);
        if (waiting != null) {
            waiting.cancel();
        }
        finishQuit(player, flush);
    }

    private void finishQuit(UUID player, Runnable flush) {
        try {
            flush.run();
        } catch (RuntimeException e) {
            log.log(Level.WARNING, "Uploading the clipboard of " + player + " failed", e);
        }
        try {
            // If someone else already claimed the player, they are waiting on us; otherwise nobody is.
            if (!channel.release(player, instanceId)) {
                channel.publishReleased(player, instanceId);
            }
        } catch (RuntimeException e) {
            log.log(Level.WARNING, "Could not announce the release of " + player, e);
        }
    }

    private void onReleased(UUID player, String from) {
        if (from.equals(instanceId)) {
            return;
        }
        Pending waiting = pending.get(player);
        if (waiting != null) {
            waiting.released(from);
        }
    }

    private Runnable guarded(Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                log.log(Level.WARNING, "Clipboard handoff task failed", e);
            }
        };
    }

    @Override
    public void close() {
        try {
            subscription.close();
        } catch (Exception e) {
            log.log(Level.FINE, "Closing the handoff subscription failed", e);
        }
        pending.values().forEach(Pending::cancel);
        pending.clear();
    }

    /** One player's join that has not been resolved yet. All state is guarded by {@code this}. */
    private final class Pending {

        private final UUID player;
        /** The load, or the quit tail once the player has left before the load happened. */
        private Runnable action;
        private String awaited;
        /** Releases that arrived before we knew whom to wait for. */
        private final Set<String> earlyReleases = new HashSet<>();
        private ScheduledFuture<?> timeoutTask;
        private boolean fired;

        Pending(UUID player, Runnable load) {
            this.player = player;
            this.action = load;
        }

        /** {@code holder == null} means nobody else had the player, so there is nothing to wait for. */
        void awaiting(String holder, Duration wait) {
            Runnable run;
            synchronized (this) {
                if (fired) {
                    return;
                }
                if (holder == null || earlyReleases.contains(holder)) {
                    run = take();
                } else {
                    awaited = holder;
                    timeoutTask = scheduler.schedule(this::fire, wait.toMillis(), TimeUnit.MILLISECONDS);
                    return;
                }
            }
            worker.execute(guarded(run));
        }

        void released(String from) {
            Runnable run;
            synchronized (this) {
                if (fired) {
                    return;
                }
                if (awaited == null) {
                    earlyReleases.add(from);
                    return;
                }
                if (!awaited.equals(from)) {
                    return;
                }
                run = take();
            }
            worker.execute(guarded(run));
        }

        /** Timeout, or an obsolete join being superseded. */
        void fire() {
            Runnable run;
            synchronized (this) {
                if (fired) {
                    return;
                }
                run = take();
            }
            worker.execute(guarded(run));
        }

        /** The player left: skip the load, but still wait for the same trigger before running the quit tail. */
        void leaveInstead(Runnable tail) {
            synchronized (this) {
                if (!fired) {
                    action = tail;
                    return;
                }
            }
            worker.execute(guarded(tail));
        }

        void cancel() {
            synchronized (this) {
                fired = true;
                if (timeoutTask != null) {
                    timeoutTask.cancel(false);
                }
            }
        }

        private Runnable take() {
            fired = true;
            if (timeoutTask != null) {
                timeoutTask.cancel(false);
            }
            pending.remove(player, this);
            return action;
        }
    }
}
