package com.tileboard.app.gameengine;

import com.tileboard.serial.board.Board;
import com.tileboard.serial.board.TileCodec;

/**
 * The extension point every concrete game implements. This interface is
 * deliberately small: the game engine owns the lifecycle (when a game
 * starts/stops, how player input is decoded, when frames are actually sent),
 * so a game only has to describe itself and react to input - it never
 * touches a serial port, a REST layer, or another game.
 *
 * <p>Adding a new game to the platform means writing one class implementing
 * this interface plus one {@link GameFactory} to construct it - nothing
 * elsewhere (controllers, session management, serial wiring) needs to
 * change. This replaces the old approach of a single central switch
 * statement enumerating every game.
 *
 * @param <T> this game's own tile representation (e.g. a color enum). The
 *            board the game receives as player input is always
 *            {@code Board<Boolean>} (touched/not touched) since that is what
 *            the hardware reports, regardless of what a game renders back.
 */
public interface Game<T> {

    GameDefinition definition();

    /** How this game's {@code T} values are translated to/from wire bytes when sent to the board. */
    TileCodec<T> tileCodec();

    /** Called once when the game becomes active. {@code context} is only valid until {@link #stop()}. */
    void start(GameContext<T> context);

    /** Called whenever the controller reports tile touches while this game is active. */
    void onPlayerInput(Board<Boolean> touchedTiles);

    /** Called once when the game is being torn down (a new game started, or the player stopped). */
    default void stop() {
    }
}
