package net.clanimg.crossClipboard.clipboard;

import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardReader;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardWriter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.math.Vector3;
import com.sk89q.worldedit.math.transform.AffineTransform;
import com.sk89q.worldedit.math.transform.Transform;
import com.sk89q.worldedit.session.ClipboardHolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Turns a player's {@link ClipboardHolder} into bytes and back, using WorldEdit's own schematic formats so it
 * behaves the same on FAWE (fast format) and plain WorldEdit (Sponge format).
 */
public final class ClipboardCodec {

    public record Encoded(byte[] data, String format, long blocks, String size) {
    }

    /**
     * @param configured {@code auto} picks FAWE's fast format when present and the standard schematic format
     *                   otherwise; anything else is looked up as a format alias such as {@code sponge.3}
     */
    public ClipboardFormat resolveFormat(String configured) throws IOException {
        ClipboardFormat format;
        if (configured.equalsIgnoreCase("auto")) {
            format = ClipboardFormats.findByAlias("fast");
            if (format == null) {
                format = ClipboardFormats.findByAlias("schem");
            }
        } else {
            format = ClipboardFormats.findByAlias(configured);
        }
        if (format == null) {
            throw new IOException("No clipboard format found for '" + configured + "'");
        }
        return format;
    }

    /** @throws ClipboardTooLargeException if the serialized clipboard would exceed {@code maxBytes} */
    public Encoded encode(ClipboardHolder holder, ClipboardFormat format, long maxBytes) throws IOException {
        Clipboard clipboard = holder.getClipboard();
        LimitedOutputStream out = new LimitedOutputStream(maxBytes);
        try (ClipboardWriter writer = format.getWriter(out)) {
            writer.write(clipboard);
        }
        BlockVector3 size = clipboard.getDimensions();
        return new Encoded(
                out.toByteArray(),
                format.getName(),
                clipboard.getRegion().getVolume(),
                size.x() + "x" + size.y() + "x" + size.z()
        );
    }

    /** @param transform coefficients as returned by {@link #transformOf}, or {@code null} for none */
    public ClipboardHolder decode(byte[] data, String formatName, double[] transform) throws IOException {
        ClipboardFormat format = ClipboardFormats.getAll().stream()
                .filter(candidate -> candidate.getName().equals(formatName))
                .findFirst()
                .orElseThrow(() -> new IOException("This server cannot read clipboard format '" + formatName + "'"));

        Clipboard clipboard;
        try (ClipboardReader reader = format.getReader(new ByteArrayInputStream(data))) {
            clipboard = reader.read();
        }
        ClipboardHolder holder = new ClipboardHolder(clipboard);
        if (transform != null) {
            holder.setTransform(new AffineTransform(transform));
        }
        return holder;
    }

    /**
     * The holder's rotation/flip as the 12 coefficients of a 3x4 affine matrix (row-major), or {@code null} if
     * there is none. Non-affine transforms are recovered by sampling where they send the axes.
     */
    public double[] transformOf(ClipboardHolder holder) {
        Transform transform = holder.getTransform();
        if (transform.isIdentity()) {
            return null;
        }
        if (transform instanceof AffineTransform affine) {
            return affine.coefficients().clone();
        }
        Vector3 origin = transform.apply(Vector3.ZERO);
        Vector3 x = transform.apply(Vector3.UNIT_X).subtract(origin);
        Vector3 y = transform.apply(Vector3.UNIT_Y).subtract(origin);
        Vector3 z = transform.apply(Vector3.UNIT_Z).subtract(origin);
        return new double[]{
                x.x(), y.x(), z.x(), origin.x(),
                x.y(), y.y(), z.y(), origin.y(),
                x.z(), y.z(), z.z(), origin.z()
        };
    }

    /** Stops a runaway serialization at the limit instead of buffering the whole clipboard first. */
    private static final class LimitedOutputStream extends ByteArrayOutputStream {

        private final long limit;

        LimitedOutputStream(long limit) {
            this.limit = limit;
        }

        @Override
        public synchronized void write(int b) {
            check(1);
            super.write(b);
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) {
            check(len);
            super.write(b, off, len);
        }

        private void check(int incoming) {
            if ((long) count + incoming > limit) {
                throw new ClipboardTooLargeException(limit);
            }
        }
    }
}
