package net.clanimg.crossClipboard.store;

/**
 * A clipboard as kept in the shared store: the serialized schematic plus the metadata needed to decode it
 * and to tell "the copy I already have" apart from "a newer copy".
 */
public record StoredClipboard(Meta meta, byte[] data) {

    /**
     * @param version    random per upload; two servers compare it to see whether they hold the same copy
     * @param format     {@code ClipboardFormat#getName()} of the writer that produced {@code data}
     * @param transform  the holder's affine transform (3x4 row-major), or {@code null} for identity
     * @param ttlSeconds remaining lifetime as reported by the store, {@code -1} when unknown
     */
    public record Meta(
            long version,
            long updatedMillis,
            String server,
            String format,
            long blocks,
            String size,
            long bytes,
            double[] transform,
            long ttlSeconds
    ) {
    }
}
