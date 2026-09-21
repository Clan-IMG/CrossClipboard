package net.clanimg.crossClipboard;

import net.clanimg.crossClipboard.sync.SyncService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

final class PlayerListener implements Listener {

    private final SyncService sync;

    PlayerListener(SyncService sync) {
        this.sync = sync;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    void onJoin(PlayerJoinEvent event) {
        sync.join(event.getPlayer());
    }

    /** Lowest, so we read the session before WorldEdit's own quit handling touches it. */
    @EventHandler(priority = EventPriority.LOWEST)
    void onQuit(PlayerQuitEvent event) {
        sync.quit(event.getPlayer());
    }
}
