package net.clanimg.crossClipboard.sync;

import java.lang.ref.WeakReference;

/**
 * Remembers that this server has a particular clipboard object in sync with a particular stored version.
 * The holder is weak so a session that WorldEdit has dropped does not keep the clipboard alive here.
 */
record Synced(long version, WeakReference<?> holder, double[] transform) {

    static Synced of(long version, Object holder, double[] transform) {
        return new Synced(version, new WeakReference<>(holder), transform);
    }
}
