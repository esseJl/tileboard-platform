package com.tileboard.engine.core;

import com.tileboard.engine.model.TileColor;
import com.tileboard.serial.board.Board;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-process, framework-free pub/sub for real board frames actually sent to
 * the gateway. Mirrors the role {@code GameEventBus} plays for game-level
 * events: a single shared instance lives for the lifetime of the
 * application (constructed once, e.g. as a Spring {@code @Bean}), while the
 * {@link BoardChannel} instances that call {@link #dispatch} are created and
 * discarded per game session / per gateway (re)connection.
 *
 * <p>This is the class an SSE (or WebSocket, logging, recording, ...)
 * component should hold a reference to and call {@link #subscribe} on, so
 * it keeps receiving frames across session restarts and gateway
 * reconnects.
 */
public final class BoardFrameBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(BoardFrameBroadcaster.class);

    private final CopyOnWriteArrayList<BoardFrameListener> listeners = new CopyOnWriteArrayList<>();

    /**
     * Registers {@code listener} to receive every future frame dispatched
     * by any {@link BoardChannel} wired to this broadcaster.
     *
     * @return a {@link Runnable} that, when invoked, removes the subscription
     */
    public Runnable subscribe(BoardFrameListener listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    public int subscriberCount() {
        return listeners.size();
    }

    /**
     * Called by {@link BoardChannel} right after a frame was actually
     * written to the gateway. Best-effort: a misbehaving listener is
     * logged and skipped rather than allowed to affect the hardware write
     * path or other listeners.
     */
    void dispatch(String sessionId, Board<TileColor> board) {
        for (BoardFrameListener listener : listeners) {
            try {
                listener.onBoardSent(sessionId, board);
            } catch (RuntimeException e) {
                log.warn("BoardFrameListener threw while handling a board frame (sessionId={})", sessionId, e);
            }
        }
    }
}
