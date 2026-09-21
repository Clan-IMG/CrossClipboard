package net.clanimg.crossClipboard.store;

/** The store could not be reached or answered with something unusable. */
public final class StoreException extends RuntimeException {

    public StoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
