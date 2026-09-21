package net.clanimg.crossClipboard.sync;

import java.util.Arrays;

/**
 * The rules for when the stored copy and the player's local clipboard may overwrite each other. Holders are
 * compared by identity: WorldEdit creates a new holder for every copy, so a different object means the player
 * has copied something new.
 */
final class SyncPolicy {

    private SyncPolicy() {
    }

    /**
     * Whether a stored clipboard should replace what is in the player's session when their handoff completes.
     *
     * <p>Whatever is there was not made this visit; it is left over from an earlier stay on this server.
     * WorldEdit keeps sessions around for a while, and FAWE by default reloads a player's clipboard from disk on
     * join. Such a leftover may be stale, because the player may have copied something new on another server
     * since, so it only stays if the store still holds the very version this server last synced. (A player who
     * copies in the fraction of a second before the handoff completes is overwritten; guarding against that would
     * have to tell it apart from FAWE's asynchronous disk reload, which it cannot.)
     *
     * @param current       the player's holder right now, or {@code null}
     * @param mark          what this server last synced for the player, or {@code null}
     * @param storedVersion version of the copy in the store
     */
    static boolean shouldRestore(Object current, Synced mark, long storedVersion) {
        if (current == null) {
            return true;
        }
        return mark == null || mark.version() != storedVersion;
    }

    /**
     * Whether to tell the player that a clipboard was loaded for them. A manual pull always says so. An automatic
     * restore stays quiet when the clipboard came from this very server: they made it here, so "loaded from
     * <this server>" tells them nothing.
     */
    static boolean shouldAnnounce(boolean manual, boolean notifyOnRestore, String origin, String thisServer) {
        return manual || (notifyOnRestore && !origin.equals(thisServer));
    }

    /** Whether uploading would just store the copy that is already stored. */
    static boolean alreadySynced(Synced mark, Object holder, double[] transform) {
        return mark != null
                && mark.holder().get() == holder
                && Arrays.equals(mark.transform(), transform);
    }
}
