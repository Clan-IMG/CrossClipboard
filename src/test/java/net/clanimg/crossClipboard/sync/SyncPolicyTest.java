package net.clanimg.crossClipboard.sync;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyncPolicyTest {

    private final Object local = new Object();
    private final Object other = new Object();

    @Test
    void takesTheStoredClipboardWhenThePlayerHasNoneLocally() {
        assertTrue(SyncPolicy.shouldRestore(null, null, 7));
        assertTrue(SyncPolicy.shouldRestore(null, Synced.of(7, local, null), 7), "the session was emptied");
    }

    @Test
    void replacesALeftoverClipboardWhenNothingWasSyncedHereBefore() {
        // e.g. FAWE reloaded it from disk after this server restarted, so no mark survived
        assertTrue(SyncPolicy.shouldRestore(local, null, 7));
    }

    @Test
    void replacesALeftoverClipboardWhenTheStoreHasMovedOn() {
        assertTrue(SyncPolicy.shouldRestore(local, Synced.of(1, local, null), 2));
    }

    @Test
    void replacesADiskRestoredLeftoverThatIsADifferentObjectThanWhatWeSynced() {
        // FAWE rebuilds the holder from disk, so identity says nothing; only the version does.
        assertTrue(SyncPolicy.shouldRestore(local, Synced.of(1, other, null), 2));
    }

    @Test
    void keepsALeftoverClipboardWhenTheStoreStillHoldsThatVersion() {
        assertFalse(SyncPolicy.shouldRestore(local, Synced.of(1, local, null), 1));
        assertFalse(SyncPolicy.shouldRestore(local, Synced.of(1, other, null), 1));
    }

    @Test
    void announcesAClipboardThatCameFromAnotherServer() {
        assertTrue(SyncPolicy.shouldAnnounce(false, true, "build", "lobby"));
    }

    @Test
    void staysQuietWhenTheClipboardCameFromThisServer() {
        assertFalse(SyncPolicy.shouldAnnounce(false, true, "build", "build"));
    }

    @Test
    void staysQuietWhenNotificationsAreOff() {
        assertFalse(SyncPolicy.shouldAnnounce(false, false, "build", "lobby"));
    }

    @Test
    void aManualPullAlwaysAnnounces() {
        assertTrue(SyncPolicy.shouldAnnounce(true, false, "build", "build"));
    }

    @Test
    void doesNotUploadWhatIsAlreadyStored() {
        double[] rotated = {0, 0, 1, 0, 0, 1, 0, 0, -1, 0, 0, 0};

        assertTrue(SyncPolicy.alreadySynced(Synced.of(1, local, null), local, null));
        assertTrue(SyncPolicy.alreadySynced(Synced.of(1, local, rotated.clone()), local, rotated));
    }

    @Test
    void uploadsWhenTheClipboardOrItsRotationChanged() {
        double[] rotated = {0, 0, 1, 0, 0, 1, 0, 0, -1, 0, 0, 0};

        assertFalse(SyncPolicy.alreadySynced(null, local, null));
        assertFalse(SyncPolicy.alreadySynced(Synced.of(1, local, null), other, null), "a new //copy");
        assertFalse(SyncPolicy.alreadySynced(Synced.of(1, local, null), local, rotated), "a //rotate");
    }
}
