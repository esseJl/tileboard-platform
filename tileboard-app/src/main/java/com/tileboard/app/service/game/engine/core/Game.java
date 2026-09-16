package com.tileboard.app.service.game.engine.core;

import com.tileboard.app.service.game.engine.context.GameContext;
import com.tileboard.app.service.game.engine.session.GameSessionManager;
import com.tileboard.serial.board.Board;
import com.tileboard.serial.board.TileCodec;

/**
 * Contract every concrete game must implement.
 *
 * <p>Games are deliberately passive: the {@link GameSessionManager}
 * owns the lifecycle (start / feed input / stop) and the {@link GameContext}
 * provides all built-in services (touch history, score, timer, path helpers, ...).
 *
 * @param <T> the tile representation this game works with (e.g. {@code TileColor}, {@code Boolean})
 */
public interface Game<T> {

    /** Static metadata; must be stable for the lifetime of the instance. */
    GameDefinition definition();

    /** Codec used to translate this game's tiles to/from the wire. */
    TileCodec<T> tileCodec();

    /**
     * Called once when the session starts. The game should initialise its
     * internal state and may already publish an initial board via {@code context}.
     */
    void start(GameContext<T> context);

    /**
     * Called on every new touch frame received from the hardware.
     * {@code touchedTiles} is a board of the same geometry where {@code true}
     * means the tile is currently pressed.
     */
    void onPlayerInput(Board<Boolean> touchedTiles);

    /** Optional clean-up; default is a no-op. */
    default void stop() {
    }
}
