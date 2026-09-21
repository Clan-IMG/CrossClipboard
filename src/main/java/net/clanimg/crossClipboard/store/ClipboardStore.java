package net.clanimg.crossClipboard.store;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/** Where clipboards live between servers. Every player has at most one entry; saving replaces it. */
public interface ClipboardStore {

    /** Atomically replaces the player's entry; it expires after {@code ttl}. */
    void save(UUID owner, StoredClipboard clipboard, Duration ttl);

    Optional<StoredClipboard> load(UUID owner);

    /** Like {@link #load} but without the payload, so it stays cheap for multi-megabyte clipboards. */
    Optional<StoredClipboard.Meta> meta(UUID owner);

    void delete(UUID owner);
}
