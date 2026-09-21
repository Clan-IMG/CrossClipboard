package net.clanimg.crossClipboard.sync;

import com.sk89q.worldedit.EmptyClipboardException;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat;
import com.sk89q.worldedit.session.ClipboardHolder;
import net.clanimg.crossClipboard.Format;
import net.clanimg.crossClipboard.Messages;
import net.clanimg.crossClipboard.Settings;
import net.clanimg.crossClipboard.clipboard.ClipboardCodec;
import net.clanimg.crossClipboard.clipboard.ClipboardTooLargeException;
import net.clanimg.crossClipboard.store.ClipboardStore;
import net.clanimg.crossClipboard.store.StoreException;
import net.clanimg.crossClipboard.store.StoredClipboard;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.Closeable;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Glue between Bukkit events, the player's WorldEdit clipboard, the codec and the shared store. WorldEdit's
 * session and Bukkit players are only touched on the main thread; serializing and talking to the store
 * happens on {@code io}.
 */
public final class SyncService {

    public static final String USE_PERMISSION = "crossclipboard.use";

    private static final Duration SHUTDOWN_BUDGET = Duration.ofSeconds(15);

    /** What the player's clipboard looked like when it was captured on the main thread. */
    private record Snapshot(ClipboardHolder holder, double[] transform) {
    }

    /** A snapshot that is already serialized, so it no longer depends on WorldEdit keeping the clipboard open. */
    private record Prepared(Snapshot snapshot, ClipboardCodec.Encoded encoded) {
    }

    private final JavaPlugin plugin;
    private final Supplier<Settings> settings;
    private final String fallbackServerName;
    private final ClipboardStore store;
    private final HandoffCoordinator handoff;
    private final ClipboardCodec codec;
    private final Messages messages;
    private final Executor io;
    private final Logger log;

    /** Players whose join we processed, so their quit is processed symmetrically whatever permissions do. */
    private final Set<UUID> tracked = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Synced> synced = new ConcurrentHashMap<>();

    public SyncService(JavaPlugin plugin, Supplier<Settings> settings, String fallbackServerName,
                       ClipboardStore store, HandoffCoordinator handoff, ClipboardCodec codec,
                       Messages messages, Executor io) {
        this.plugin = plugin;
        this.settings = settings;
        this.fallbackServerName = fallbackServerName;
        this.store = store;
        this.handoff = handoff;
        this.codec = codec;
        this.messages = messages;
        this.io = io;
        this.log = plugin.getLogger();
    }

    // ---- automatic sync ----

    /** Main thread. */
    public void join(Player player) {
        if (!player.hasPermission(USE_PERMISSION)) {
            return;
        }
        UUID id = player.getUniqueId();
        tracked.add(id);
        ClipboardHolder atJoin = holderOf(player);
        handoff.onJoin(id, () -> restore(id, atJoin));
    }

    /**
     * Main thread, before WorldEdit's own quit handling. The clipboard is serialized right here rather than on
     * a worker: FAWE closes a leaving player's clipboard shortly after this event, and an upload that reads it
     * later would find it gone. Only the network part is left for the worker.
     */
    public void quit(Player player) {
        UUID id = player.getUniqueId();
        if (!tracked.remove(id)) {
            return;
        }
        String name = player.getName();
        Prepared prepared = prepareForQuit(id, name, player);
        handoff.onQuit(id, () -> flush(id, name, prepared));
    }

    /**
     * Main thread, from onDisable: Bukkit disables plugins before it disconnects players. Uploads block the
     * server from stopping, so it gives up after {@link #SHUTDOWN_BUDGET} rather than hang on a dead Redis.
     */
    public void shutdown() {
        long deadline = System.nanoTime() + SHUTDOWN_BUDGET.toNanos();
        for (UUID id : List.copyOf(tracked)) {
            if (System.nanoTime() > deadline) {
                log.warning("Out of time while shutting down; clipboards of the remaining players were not synced");
                break;
            }
            Player player = Bukkit.getPlayer(id);
            String name = player == null ? id.toString() : player.getName();
            Prepared prepared = player == null ? null : prepareForQuit(id, name, player);
            handoff.onQuitNow(id, () -> flush(id, name, prepared));
        }
        tracked.clear();
    }

    /** Worker thread: loads the stored clipboard once the handoff says it is safe. */
    private void restore(UUID id, ClipboardHolder atJoin) {
        try {
            Optional<StoredClipboard.Meta> stored = store.meta(id);
            if (stored.isEmpty()) {
                return;
            }
            Synced mark = synced.get(id);
            long version = stored.get().version();
            boolean proceed = onMain(() -> {
                Player player = Bukkit.getPlayer(id);
                return player != null && SyncPolicy.shouldRestore(holderOf(player), atJoin, mark, version);
            });
            if (!proceed) {
                return;
            }

            Optional<StoredClipboard> clipboard = store.load(id);
            if (clipboard.isEmpty()) {
                return;
            }
            StoredClipboard.Meta meta = clipboard.get().meta();
            ClipboardHolder holder = codec.decode(clipboard.get().data(), meta.format(), meta.transform());
            onMain(() -> apply(id, atJoin, false, holder, meta));
        } catch (Exception e) {
            log.log(Level.WARNING, "Could not restore the clipboard of " + id, e);
        }
    }

    /** Main thread: serializes what the player leaves behind, unless the store already has exactly that. */
    private Prepared prepareForQuit(UUID id, String name, Player player) {
        Snapshot snapshot = capture(player);
        if (snapshot == null || SyncPolicy.alreadySynced(synced.get(id), snapshot.holder(), snapshot.transform())) {
            return null;
        }
        try {
            return new Prepared(snapshot, encode(snapshot));
        } catch (ClipboardTooLargeException e) {
            log.info("Clipboard of " + name + " is over the " + Format.bytes(e.limitBytes())
                    + " limit and was not synced");
        } catch (Exception e) {
            log.log(Level.WARNING, "Could not serialize the clipboard of " + name, e);
        }
        return null;
    }

    /** Worker thread: uploads what {@link #prepareForQuit} produced. */
    private void flush(UUID id, String name, Prepared prepared) {
        if (prepared == null) {
            return;
        }
        try {
            save(id, prepared.snapshot(), prepared.encoded());
        } catch (Exception e) {
            log.log(Level.WARNING, "Could not sync the clipboard of " + name, e);
        }
    }

    // ---- commands ----

    /** Main thread. */
    public void push(Player player) {
        Snapshot snapshot = capture(player);
        if (snapshot == null) {
            player.sendMessage(messages.get("nothing-to-push"));
            return;
        }
        UUID id = player.getUniqueId();
        io.execute(() -> {
            try {
                StoredClipboard.Meta meta = save(id, snapshot, encode(snapshot));
                player.sendMessage(messages.get("pushed", Map.of(
                        "blocks", Long.toString(meta.blocks()),
                        "bytes", Format.bytes(meta.bytes()))));
            } catch (ClipboardTooLargeException e) {
                player.sendMessage(messages.get("too-large", Map.of("limit", Format.bytes(e.limitBytes()))));
            } catch (StoreException e) {
                storeFailure(player, e);
            } catch (Exception e) {
                processFailure(player, e);
            }
        });
    }

    /** Any thread. */
    public void pull(Player player) {
        UUID id = player.getUniqueId();
        io.execute(() -> {
            try {
                Optional<StoredClipboard> clipboard = store.load(id);
                if (clipboard.isEmpty()) {
                    player.sendMessage(messages.get("nothing-stored"));
                    return;
                }
                StoredClipboard.Meta meta = clipboard.get().meta();
                ClipboardHolder holder = codec.decode(clipboard.get().data(), meta.format(), meta.transform());
                onMain(() -> apply(id, null, true, holder, meta));
            } catch (StoreException e) {
                storeFailure(player, e);
            } catch (Exception e) {
                processFailure(player, e);
            }
        });
    }

    /** Main thread. */
    public void status(Player player) {
        boolean local = holderOf(player) != null;
        UUID id = player.getUniqueId();
        io.execute(() -> {
            try {
                player.sendMessage(messages.get(local ? "status-local" : "status-no-local"));
                Optional<StoredClipboard.Meta> meta = store.meta(id);
                if (meta.isEmpty()) {
                    player.sendMessage(messages.get("status-none"));
                    return;
                }
                StoredClipboard.Meta stored = meta.get();
                long ageSeconds = Instant.now().getEpochSecond() - stored.updatedMillis() / 1000;
                player.sendMessage(messages.get("status-stored", Map.of(
                        "blocks", Long.toString(stored.blocks()),
                        "size", stored.size(),
                        "bytes", Format.bytes(stored.bytes()),
                        "server", stored.server(),
                        "age", Format.duration(ageSeconds),
                        "expires", stored.ttlSeconds() < 0 ? "?" : Format.duration(stored.ttlSeconds()))));
            } catch (StoreException e) {
                storeFailure(player, e);
            }
        });
    }

    /** Any thread. Removes the stored copy; the clipboard the player holds locally is left alone. */
    public void clear(Player player) {
        UUID id = player.getUniqueId();
        io.execute(() -> {
            try {
                store.delete(id);
                synced.remove(id);
                player.sendMessage(messages.get("cleared"));
            } catch (StoreException e) {
                storeFailure(player, e);
            }
        });
    }

    /** Forgets marks whose clipboard has been garbage collected. */
    public void pruneMarks() {
        synced.values().removeIf(mark -> mark.holder().get() == null);
    }

    // ---- internals ----

    private ClipboardCodec.Encoded encode(Snapshot snapshot) throws IOException {
        Settings current = settings.get();
        ClipboardFormat format = codec.resolveFormat(current.format());
        return codec.encode(snapshot.holder(), format, current.maxBytes());
    }

    private StoredClipboard.Meta save(UUID id, Snapshot snapshot, ClipboardCodec.Encoded encoded) {
        long version = ThreadLocalRandom.current().nextLong();
        StoredClipboard.Meta meta = new StoredClipboard.Meta(
                version, System.currentTimeMillis(), serverName(), encoded.format(), encoded.blocks(),
                encoded.size(), encoded.data().length, snapshot.transform(), -1);
        store.save(id, new StoredClipboard(meta, encoded.data()), settings.get().ttl());
        synced.put(id, Synced.of(version, snapshot.holder(), snapshot.transform()));
        return meta;
    }

    /**
     * Main thread. Puts a decoded clipboard into the player's session.
     *
     * @param expected the holder the session must still have (the one seen at join), ignored when {@code force}
     */
    private boolean apply(UUID id, ClipboardHolder expected, boolean force, ClipboardHolder holder,
                          StoredClipboard.Meta meta) {
        Player player = Bukkit.getPlayer(id);
        if (player == null || (!force && holderOf(player) != expected)) {
            discard(holder);
            return false;
        }
        WorldEdit.getInstance().getSessionManager().get(BukkitAdapter.adapt(player)).setClipboard(holder);
        synced.put(id, Synced.of(meta.version(), holder, meta.transform()));
        if (force || settings.get().notifyOnRestore()) {
            player.sendMessage(messages.get("restored", Map.of(
                    "server", meta.server(),
                    "blocks", Long.toString(meta.blocks()),
                    "size", meta.size())));
        }
        return true;
    }

    private Snapshot capture(Player player) {
        ClipboardHolder holder = holderOf(player);
        return holder == null ? null : new Snapshot(holder, codec.transformOf(holder));
    }

    private ClipboardHolder holderOf(Player player) {
        LocalSession session = WorldEdit.getInstance().getSessionManager().getIfPresent(BukkitAdapter.adapt(player));
        if (session == null) {
            return null;
        }
        try {
            return session.getClipboard();
        } catch (EmptyClipboardException e) {
            return null;
        }
    }

    /** FAWE holders are closeable (disk-backed clipboards); plain WorldEdit's are not. */
    private static void discard(ClipboardHolder holder) {
        if (holder instanceof Closeable closeable) {
            try {
                closeable.close();
            } catch (IOException ignored) {
                // Nothing useful to do about a clipboard we are throwing away anyway.
            }
        }
    }

    private <T> T onMain(Callable<T> task) throws Exception {
        if (Bukkit.isPrimaryThread()) {
            return task.call();
        }
        return Bukkit.getScheduler().callSyncMethod(plugin, task).get(10, TimeUnit.SECONDS);
    }

    private String serverName() {
        String configured = settings.get().serverName();
        return configured.isBlank() ? fallbackServerName : configured;
    }

    private void storeFailure(Player player, StoreException e) {
        log.log(Level.WARNING, e.getMessage(), e.getCause());
        player.sendMessage(messages.get("store-error"));
    }

    private void processFailure(Player player, Exception e) {
        log.log(Level.WARNING, "Clipboard sync failed for " + player.getName(), e);
        player.sendMessage(messages.get("process-error", Map.of("reason", String.valueOf(e.getMessage()))));
    }
}
