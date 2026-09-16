package com.tileboard.app.gameengine.games.jump;

import com.tileboard.app.gameengine.Cancellable;
import com.tileboard.app.gameengine.Game;
import com.tileboard.app.gameengine.GameContext;
import com.tileboard.app.gameengine.GameDefinition;
import com.tileboard.app.gameengine.TileColor;
import com.tileboard.app.gameengine.TileColors;
import com.tileboard.serial.board.Board;
import com.tileboard.serial.board.Position;
import com.tileboard.serial.board.TileCodec;

import java.util.List;
import java.util.Set;

/**
 * Reflex/avoidance game: a colored band sweeps back and forth across the
 * board (its shape - row, column, diagonal, ... - coming entirely from a
 * {@link JumpPattern}) and the player must avoid touching it. Every touch
 * that lands on the band costs one of a limited number of lives; running
 * out of lives before the round's duration elapses ends the round in a
 * loss, surviving the full duration is a win.
 *
 * <p>This single class replaces what the previous implementation split into
 * five nearly-identical subclasses (one per sweep shape, each re-deriving
 * its own bounce/lose/win logic with subtly different bugs). Here the sweep
 * shape is pure {@link JumpPattern} data and everything else - timing,
 * lives, win/lose - is written exactly once.
 *
 * <p>{@link #start} runs on the HTTP request thread that starts the game;
 * {@link #onPlayerInput} runs on the gateway's callback thread; the tick
 * scheduled via {@link GameContext#scheduleAtFixedRate} runs on the
 * session's own clock thread. All mutable state is therefore only ever
 * touched inside a block synchronized on {@link #lock}.
 */
final class JumpGame implements Game<TileColor> {

    private enum Outcome {NONE, WON, LOST}

    private final GameDefinition definition;
    private final JumpPattern pattern;
    private final JumpTuning tuning;
    private final int width;
    private final int height;
    private final Object lock = new Object();

    private GameContext<TileColor> context;
    private Cancellable ticking;
    private List<List<Position>> frames;
    private int step;
    private int direction = 1;
    private int livesRemaining;
    private Outcome outcome = Outcome.NONE;

    JumpGame(GameDefinition definition, JumpPattern pattern, JumpTuning tuning, int width, int height) {
        this.definition = definition;
        this.pattern = pattern;
        this.tuning = tuning;
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
        synchronized (lock) {
            this.context = context;
            this.frames = pattern.framesFor(width, height);
            this.step = 0;
            this.direction = 1;
            this.livesRemaining = tuning.lives();
            this.outcome = Outcome.NONE;
            publishCurrentFrame();
            this.ticking = context.scheduleAtFixedRate(tuning.tickInterval(), this::tick);
        }
    }

    private void tick() {
        synchronized (lock) {
            if (outcome != Outcome.NONE) {
                return;
            }
            if (context.elapsed().compareTo(tuning.roundDuration()) >= 0) {
                finish(Outcome.WON);
                return;
            }
            advanceStep();
            publishCurrentFrame();
        }
    }

    /** Bounces {@link #step} back and forth across {@link #frames} instead of wrapping or stopping at the end. */
    private void advanceStep() {
        if (frames.size() <= 1) {
            return;
        }
        step += direction;
        if (step >= frames.size() - 1) {
            step = frames.size() - 1;
            direction = -1;
        } else if (step <= 0) {
            step = 0;
            direction = 1;
        }
    }

    @Override
    public void onPlayerInput(Board<Boolean> touchedTiles) {
        synchronized (lock) {
            if (outcome != Outcome.NONE || context == null) {
                return;
            }
            Set<Position> band = Set.copyOf(frames.get(step));
            boolean hitBand = touchedTiles.positionsWhere(Boolean.TRUE::equals).stream().anyMatch(band::contains);
            if (!hitBand) {
                return;
            }
            livesRemaining--;
            if (livesRemaining <= 0) {
                finish(Outcome.LOST);
            }
        }
    }

    /** Must be called while holding {@link #lock}. Stops the clock and publishes a final win/lose board. */
    private void finish(Outcome result) {
        outcome = result;
        if (ticking != null) {
            ticking.cancel();
            ticking = null;
        }
        Board<TileColor> finalBoard = new Board<>(width, height, result == Outcome.WON ? TileColor.WHITE : TileColor.RED);
        context.publish(finalBoard);
    }

    /** Must be called while holding {@link #lock}. */
    private void publishCurrentFrame() {
        Board<TileColor> board = new Board<>(width, height, TileColor.OFF);
        for (Position position : frames.get(step)) {
            board.set(position, TileColor.GREEN);
        }
        context.publish(board);
    }

    @Override
    public void stop() {
        synchronized (lock) {
            if (ticking != null) {
                ticking.cancel();
                ticking = null;
            }
            context = null;
        }
    }
}
