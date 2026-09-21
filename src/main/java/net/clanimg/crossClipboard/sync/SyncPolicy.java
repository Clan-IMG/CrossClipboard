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
     * Whether a stored clipboard should replace what the player has locally.
     *
     * @param current      the player's holder right now
     * @param atJoin       the player's holder when they joined this server
     * @param mark         what this server last synced for the player, or {@code null}
     * @param storedVersion version of the copy in the store
     */
    static boolean shouldRestore(Object current, Object atJoin, Synced mark, long storedVersion) {
        if (current != atJoin) {
            // They copied something on this server since joining; that is newer than anything stored.
            return false;
        }
        if (current == null) {
            return true;
        }
        // They arrived with a clipboard already in the session. Only replace it if it is the very copy this
        // server synced earlier and the store has moved on since; anything else is local work we must keep.
        return mark != null && mark.holder().get() == current && mark.version() != storedVersion;
    }

    /** Whether uploading would just store the copy that is already stored. */
    static boolean alreadySynced(Synced mark, Object holder, double[] transform) {
        return mark != null
                && mark.holder().get() == holder
                && Arrays.equals(mark.transform(), transform);
    }
}
