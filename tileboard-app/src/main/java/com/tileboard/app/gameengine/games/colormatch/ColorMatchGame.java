package com.tileboard.app.gameengine.games.colormatch;

import com.tileboard.app.gameengine.Cancellable;
import com.tileboard.app.gameengine.Game;
import com.tileboard.app.gameengine.GameContext;
import com.tileboard.app.gameengine.GameDefinition;
import com.tileboard.app.gameengine.TileColor;
import com.tileboard.app.gameengine.TileColors;
import com.tileboard.serial.board.Board;
import com.tileboard.serial.board.Position;
import com.tileboard.serial.board.TileCodec;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Classic memory/concentration game: a number of color pairs are placed on
 * random tiles, briefly shown, then hidden. The player touches two tiles at
 * a time to reveal them; a match stays revealed permanently, a mismatch
 * flips back over after a short pause. The round ends once every pair has
 * been found.
 *
 * <p>State transitions driven purely by touch (a pick arriving, a pair
 * matching) happen instantly in {@link #onPlayerInput}; transitions driven
 * by time (the initial reveal ending, a mismatched pair flipping back)
 * happen on {@link #tick}, scheduled via {@link GameContext#scheduleAtFixedRate}.
 * Both run on different threads, so every access to the game's state is
 * inside a block synchronized on {@link #lock}.
 */
final class ColorMatchGame implements Game<TileColor> {

    private enum Phase {REVEAL_ALL, AWAIT_PICKS, EVALUATING, WON}

    private static final Duration TICK_INTERVAL = Duration.ofMillis(150);

    private final GameDefinition definition;
    private final ColorMatchTuning tuning;
    private final int width;
    private final int height;
    private final Object lock = new Object();

    private GameContext<TileColor> context;
    private Cancellable ticking;
    private Map<Position, TileColor> assignment;
    private Set<Position> solved;
    private Phase phase;
    private Instant phaseStartedAt;
    private Position firstPick;
    private Position secondPick;
    private int solvedPairs;

    ColorMatchGame(GameDefinition definition, ColorMatchTuning tuning, int width, int height) {
        this.definition = definition;
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
            this.assignment = randomAssignment();
            this.solved = new HashSet<>();
            this.phase = Phase.REVEAL_ALL;
            this.phaseStartedAt = Instant.now();
            this.firstPick = null;
            this.secondPick = null;
            this.solvedPairs = 0;
            publishCurrentState();
            this.ticking = context.scheduleAtFixedRate(TICK_INTERVAL, this::tick);
        }
    }

    private Map<Position, TileColor> randomAssignment() {
        List<Position> allPositions = new ArrayList<>(width * height);
        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {
                allPositions.add(new Position(row, col));
            }
        }
        Collections.shuffle(allPositions, ThreadLocalRandom.current());

        Map<Position, TileColor> result = new HashMap<>();
        int nextPosition = 0;
        for (int pair = 0; pair < tuning.pairCount(); pair++) {
            TileColor color = ColorMatchTuning.PALETTE[pair];
            result.put(allPositions.get(nextPosition++), color);
            result.put(allPositions.get(nextPosition++), color);
        }
        return result;
    }

    private void tick() {
        synchronized (lock) {
            switch (phase) {
                case REVEAL_ALL -> {
                    if (Duration.between(phaseStartedAt, Instant.now()).compareTo(tuning.revealAllDuration()) >= 0) {
                        enterPhase(Phase.AWAIT_PICKS);
                    }
                }
                case EVALUATING -> {
                    if (Duration.between(phaseStartedAt, Instant.now()).compareTo(tuning.evaluateDelay()) >= 0) {
                        evaluatePicks();
                    }
                }
                case AWAIT_PICKS, WON -> {
                    // AWAIT_PICKS is driven by onPlayerInput, and WON is terminal: nothing to do on a tick for either.
                }
            }
            publishCurrentState();
        }
    }

    @Override
    public void onPlayerInput(Board<Boolean> touchedTiles) {
        synchronized (lock) {
            if (phase != Phase.AWAIT_PICKS) {
                return;
            }
            for (Position touched : touchedTiles.positionsWhere(Boolean.TRUE::equals)) {
                if (!isEligiblePick(touched)) {
                    continue;
                }
                if (firstPick == null) {
                    firstPick = touched;
                } else {
                    secondPick = touched;
                    enterPhase(Phase.EVALUATING);
                }
                break;
            }
            publishCurrentState();
        }
    }

    private boolean isEligiblePick(Position position) {
        return assignment.containsKey(position) && !solved.contains(position) && !position.equals(firstPick);
    }

    /** Must be called while holding {@link #lock}. */
    private void evaluatePicks() {
        TileColor firstColor = assignment.get(firstPick);
        TileColor secondColor = assignment.get(secondPick);
        if (firstColor == secondColor) {
            solved.add(firstPick);
            solved.add(secondPick);
            solvedPairs++;
        }
        firstPick = null;
        secondPick = null;
        enterPhase(solvedPairs >= tuning.pairCount() ? Phase.WON : Phase.AWAIT_PICKS);
    }

    /** Must be called while holding {@link #lock}. */
    private void enterPhase(Phase next) {
        phase = next;
        phaseStartedAt = Instant.now();
        if (next == Phase.WON && ticking != null) {
            ticking.cancel();
            ticking = null;
        }
    }

    /** Must be called while holding {@link #lock}. */
    private void publishCurrentState() {
        Board<TileColor> board = new Board<>(width, height, TileColor.OFF);
        for (Map.Entry<Position, TileColor> entry : assignment.entrySet()) {
            Position position = entry.getKey();
            TileColor color = entry.getValue();
            boolean faceUp = phase == Phase.REVEAL_ALL || phase == Phase.WON
                    || solved.contains(position)
                    || position.equals(firstPick) || position.equals(secondPick);
            board.set(position, faceUp ? color : TileColor.WHITE);
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
