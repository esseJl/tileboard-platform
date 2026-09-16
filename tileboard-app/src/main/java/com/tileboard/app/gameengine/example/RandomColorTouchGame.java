package com.tileboard.app.gameengine.example;

import com.tileboard.app.gameengine.Game;
import com.tileboard.app.gameengine.GameContext;
import com.tileboard.app.gameengine.GameDefinition;
import com.tileboard.app.gameengine.TileColor;
import com.tileboard.app.gameengine.TileColors;
import com.tileboard.serial.board.Board;
import com.tileboard.serial.board.Position;
import com.tileboard.serial.board.TileCodec;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Every time a tile is reported touched, it is repainted with a random
 * color from {@link #PALETTE} and the whole board is resent; tiles that
 * aren't touched keep whatever color they already had (a persistent "paint"
 * board, not a momentary flash tied to the finger staying down).
 *
 * <p>{@link #start} runs on whichever thread calls {@code GameSessionManager.startGame}
 * (an HTTP request thread); {@link #onPlayerInput} runs on the gateway's
 * callback thread. Those are two different threads, so {@link #context} and
 * {@link #board} are {@code volatile}: {@code GameSessionManager} only
 * publishes this game instance (via its own volatile {@code activeGame}
 * field) after {@link #start} has returned, which combined with these
 * fields being volatile guarantees the callback thread sees the fields this
 * class set during {@link #start} - plain fields would not carry that
 * guarantee across threads even though the two methods never overlap in time.
 */
final class RandomColorTouchGame implements Game<TileColor> {

    private static final TileColor[] PALETTE = {
            TileColor.RED, TileColor.GREEN, TileColor.BLUE,
            TileColor.PINK, TileColor.LIGHT_BLUE, TileColor.Yellow, TileColor.WHITE
    };

    private final GameDefinition definition;
    private final int width;
    private final int height;

    private volatile GameContext<TileColor> context;
    private volatile Board<TileColor> board;

    RandomColorTouchGame(GameDefinition definition, int width, int height) {
        this.definition = definition;
        this.width = width;
        this.height = height;
    }

    @Override
    public GameDefinition definition() {
        return definition;
    }

    @Override
    public TileCodec<TileColor> tileCodec() {
        return TileColors.codec();
    }

    @Override
    public void start(GameContext<TileColor> context) {
        this.context = context;
        this.board = new Board<>(width, height, TileColor.OFF);
        context.publish(board);
    }

    @Override
    public void onPlayerInput(Board<Boolean> touchedTiles) {
        if (context == null || board == null) {
            return;
        }
        List<Position> touched = touchedTiles.positionsWhere(Boolean.TRUE::equals);
        if (touched.isEmpty()) {
            return;
        }
        for (Position position : touched) {
            board.set(position, randomColor());
        }
        context.publish(board);
    }

    @Override
    public void stop() {
        context = null;
        board = null;
    }

    private TileColor randomColor() {
        return PALETTE[ThreadLocalRandom.current().nextInt(PALETTE.length)];
    }
}
