package com.tileboard.engine.core;

import com.tileboard.engine.model.TileColor;
import com.tileboard.serial.board.Board;

/**
 * Observes every board frame that a {@link BoardChannel} actually transmits
 * to the gateway (after coalescing/dedup — see {@link BoardChannel}'s class
 * docs). Registered listeners never receive a frame that was suppressed
 * because it was identical to the last one sent.
 *
 * <p>Implementations are called on whichever thread performed the send
 * (game tick thread, feature thread, etc.), <em>after</em> the internal
 * gateway-write lock has been released. Implementations must therefore:
 * <ul>
 *   <li>be fast/non-blocking — do not do slow I/O directly here; hand off
 *       to your own executor if broadcasting is expensive (e.g. many SSE
 *       subscribers), or return quickly to avoid delaying the caller
 *       (e.g. a game tick).</li>
 *   <li>never throw — any exception is caught and logged by the
 *       {@link BoardFrameBroadcaster}, but throwing is still bad practice
 *       and may indicate a bug in the listener.</li>
 * </ul>
 */
@FunctionalInterface
public interface BoardFrameListener {

    /**
     * @param sessionId the session that owns the board that was sent, or
     *                  {@code null} if the {@link BoardChannel} was created
     *                  outside of a game session
     * @param board     the exact board state that was just transmitted to
     *                  the gateway (safe to retain — it's an immutable
     *                  snapshot, not the live buffer)
     */
    void onBoardSent(String sessionId, Board<TileColor> board);
}
