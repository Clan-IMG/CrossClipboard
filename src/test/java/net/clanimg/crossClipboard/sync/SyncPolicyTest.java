package net.clanimg.crossClipboard.sync;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyncPolicyTest {

    private final Object local = new Object();
    private final Object other = new Object();

    @Test
    void takesTheStoredClipboardWhenThePlayerHasNoneLocally() {
        assertTrue(SyncPolicy.shouldRestore(null, null, null, 7));
    }

    @Test
    void keepsWhatThePlayerCopiedSinceJoining() {
        assertFalse(SyncPolicy.shouldRestore(other, null, null, 7));
        assertFalse(SyncPolicy.shouldRestore(other, local, Synced.of(1, local, null), 7));
    }

    @Test
    void keepsALocalClipboardThatWasNeverSynced() {
        assertFalse(SyncPolicy.shouldRestore(local, local, null, 7));
    }

    @Test
    void replacesTheLocalCopyOnlyWhenTheStoreHasMovedOn() {
        Synced mark = Synced.of(1, local, null);

        assertTrue(SyncPolicy.shouldRestore(local, local, mark, 2));
        assertFalse(SyncPolicy.shouldRestore(local, local, mark, 1), "already holds this version");
    }

    @Test
    void keepsALocalClipboardThatIsNotTheOneWeSynced() {
        assertFalse(SyncPolicy.shouldRestore(local, local, Synced.of(1, other, null), 2));
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
