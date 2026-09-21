package net.clanimg.crossClipboard.store;

import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * Lets servers agree on who currently "has" a player, so the server a player moves to knows when the one
 * they left has finished uploading. All calls are per player and never block another player's handoff.
 */
public interface HandoffChannel {

    /**
     * Atomically records that {@code instanceId} now hosts the player.
     *
     * @return whichever instance hosted the player before, or {@code null} if none did
     */
    String claim(UUID player, String instanceId);

    /**
     * Removes the record if it still belongs to {@code instanceId}.
     *
     * @return {@code true} if it was removed, {@code false} if another instance had already claimed the player
     */
    boolean release(UUID player, String instanceId);

    /** Tells every instance that {@code instanceId} is done uploading for the player. */
    void publishReleased(UUID player, String instanceId);

    /** Calls {@code listener(player, releasingInstance)} for every release published by any instance. */
    AutoCloseable subscribe(BiConsumer<UUID, String> listener);
}
