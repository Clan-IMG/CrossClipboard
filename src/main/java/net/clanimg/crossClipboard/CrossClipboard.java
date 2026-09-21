package net.clanimg.crossClipboard;

import net.clanimg.crossClipboard.clipboard.ClipboardCodec;
import net.clanimg.crossClipboard.store.RedisBackend;
import net.clanimg.crossClipboard.store.StoreException;
import net.clanimg.crossClipboard.sync.HandoffCoordinator;
import net.clanimg.crossClipboard.sync.SyncService;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class CrossClipboard extends JavaPlugin {

    private volatile Settings settings;
    private Messages messages;
    private RedisBackend backend;
    private ExecutorService io;
    private ScheduledExecutorService scheduler;
    private HandoffCoordinator handoff;
    private SyncService sync;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        try {
            settings = Settings.load(getConfig());
        } catch (IllegalArgumentException e) {
            getLogger().severe("Invalid config.yml: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        if (!worldEditPresent()) {
            getLogger().severe("FastAsyncWorldEdit or WorldEdit is required but not enabled");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        messages = new Messages(this, settings.language());

        // Unique per process, never configured: two cloned servers must not be able to share an identity.
        String instanceId = UUID.randomUUID().toString();
        io = Executors.newFixedThreadPool(4, daemonThreads("CrossClipboard-IO"));
        scheduler = Executors.newSingleThreadScheduledExecutor(daemonThreads("CrossClipboard-Timer"));

        backend = new RedisBackend(settings.redis(), settings.keyPrefix(), getLogger());
        try {
            backend.ping();
            getLogger().info("Connected to Redis at " + settings.redis().host() + ":" + settings.redis().port());
        } catch (StoreException e) {
            getLogger().warning(e.getMessage() + " (" + e.getCause().getMessage()
                    + "). Clipboards will not sync until Redis is reachable.");
        }

        ClipboardCodec codec = new ClipboardCodec();
        handoff = new HandoffCoordinator(backend, instanceId, () -> settings.handoffTimeout(), scheduler, io,
                getLogger(), message -> {
                    if (settings.debug()) {
                        getLogger().info("[debug] " + message);
                    }
                });
        sync = new SyncService(this, () -> settings, "server-" + instanceId.substring(0, 4), backend, handoff,
                codec, messages, io);

        getServer().getPluginManager().registerEvents(new PlayerListener(sync), this);
        CrossClipboardCommand command = new CrossClipboardCommand(this, sync, messages);
        getCommand("crossclipboard").setExecutor(command);
        getCommand("crossclipboard").setTabCompleter(command);

        scheduler.scheduleWithFixedDelay(sync::pruneMarks, 10, 10, TimeUnit.MINUTES);

        try {
            getLogger().info("Syncing clipboards as format '" + codec.resolveFormat(settings.format()).getName() + "'");
        } catch (IOException e) {
            getLogger().warning(e.getMessage() + " - check sync.format in config.yml");
        }

        // Covers players already online when the plugin is loaded late, so their quit is handled as well.
        for (Player player : Bukkit.getOnlinePlayers()) {
            sync.join(player);
        }
    }

    @Override
    public void onDisable() {
        if (sync != null) {
            sync.shutdown();
        }
        if (handoff != null) {
            handoff.close();
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        if (io != null) {
            io.shutdown();
            try {
                if (!io.awaitTermination(10, TimeUnit.SECONDS)) {
                    getLogger().warning("Clipboard uploads were still running at shutdown");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (backend != null) {
            backend.close();
        }
    }

    /** Applies everything except the Redis connection, which is only read at startup. */
    void reloadSettings(CommandSender sender) {
        Settings previous = settings;
        Settings reloaded;
        try {
            reloadConfig();
            reloaded = Settings.load(getConfig());
        } catch (IllegalArgumentException e) {
            sender.sendMessage("Invalid config.yml, keeping the previous settings: " + e.getMessage());
            return;
        }
        settings = reloaded;
        messages.reload(reloaded.language());
        sender.sendMessage(messages.get("reloaded"));
        if (!reloaded.redis().equals(previous.redis()) || !reloaded.keyPrefix().equals(previous.keyPrefix())) {
            sender.sendMessage(messages.get("restart-required"));
        }
    }

    private boolean worldEditPresent() {
        var plugins = getServer().getPluginManager();
        return plugins.isPluginEnabled("FastAsyncWorldEdit") || plugins.isPluginEnabled("WorldEdit");
    }

    private static ThreadFactory daemonThreads(String prefix) {
        AtomicInteger counter = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, prefix + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
