package net.clanimg.crossClipboard.clipboard;

/** Unchecked so it can propagate out of the schematic writer's stream calls. */
public final class ClipboardTooLargeException extends RuntimeException {

    private final long limitBytes;

    public ClipboardTooLargeException(long limitBytes) {
        super("Serialized clipboard exceeds the limit of " + limitBytes + " bytes");
        this.limitBytes = limitBytes;
    }

    public long limitBytes() {
        return limitBytes;
    }
}
