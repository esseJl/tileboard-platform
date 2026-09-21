package com.tileboard.engine.feature;

import com.tileboard.engine.model.TileColor;
import com.tileboard.serial.board.Board;
import com.tileboard.serial.board.Position;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Animation system for displaying different game states on the tileboard.
 * Exactly one animation runs at a time; starting a new one cooperatively cancels the previous one.
 * A superseded animation's future completes as cancelled (never normally).
 */
public final class AnimationSystem {

    private final int width;
    private final int height;
    private final Consumer<Board<TileColor>> boardPublisher;
    private final ExecutorService executor;
    private final Random rng;
    private final AtomicLong generation = new AtomicLong(0);
    private final Object runLock = new Object();
    private volatile Future<?> currentTask;

    public AnimationSystem(int width, int height, Consumer<Board<TileColor>> boardPublisher) {
        this(width, height, boardPublisher, new Random());
    }

    /**
     * Visible for tests: inject a seeded RNG for deterministic sparkle/twinkle/crumble assertions.
     */
    AnimationSystem(int width, int height, Consumer<Board<TileColor>> boardPublisher, Random rng) {
        this.width = width;
        this.height = height;
        this.boardPublisher = boardPublisher;
        this.rng = rng;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "tileboard-animation");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Cancels the running animation and starts {@code body}. The returned future completes normally when the
     * body finishes, exceptionally if it fails, and as cancelled if it is superseded or the system shuts down.
     */
    private CompletableFuture<Void> run(Consumer<RunToken> body) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        synchronized (runLock) {
            Future<?> previous = currentTask;
            if (previous != null) previous.cancel(true);
            long myGeneration = generation.incrementAndGet();
            RunToken token = new RunToken(myGeneration);
            try {
                Future<?> submitted = executor.submit(() -> {
                    try {
                        body.accept(token);
                        if (token.isCancelled()) {
                            result.cancel(false);
                        } else {
                            result.complete(null);
                        }
                    } catch (AnimationCancelledException cancelled) {
                        result.cancel(false);
                    } catch (RuntimeException e) {
                        result.completeExceptionally(e);
                    } finally {
                        Thread.interrupted();
                    }
                });
                currentTask = submitted;
            } catch (RejectedExecutionException e) {
                result.completeExceptionally(e);
            }
        }
        return result;
    }

    /**
     * Cancels whatever animation is currently running (no-op if none). Idempotent.
     */
    public void cancelCurrent() {
        synchronized (runLock) {
            generation.incrementAndGet();
            Future<?> task = currentTask;
            if (task != null) task.cancel(true);
            currentTask = null;
        }
    }

    private Board<TileColor> ringBoard(Position center, TileColor color, int radius) {
        Board<TileColor> board = new Board<>(width, height, TileColor.OFF);
        board.forEach((row, col, tile) -> {
            int dist = Math.max(Math.abs(row - center.row()), Math.abs(col - center.col()));
            if (dist == radius) board.set(row, col, color);
        });
        return board;
    }

    private Board<TileColor> ringBandBoard(Position center, TileColor color, int outerRadius) {
        Board<TileColor> board = new Board<>(width, height, TileColor.OFF);
        board.forEach((row, col, tile) -> {
            int dist = Math.max(Math.abs(row - center.row()), Math.abs(col - center.col()));
            if (dist <= outerRadius && dist >= outerRadius - 1) board.set(row, col, color);
        });
        return board;
    }

    private void clearBoard() {
        boardPublisher.accept(new Board<>(width, height, TileColor.OFF));
    }

    public CompletableFuture<Void> playCountdown() {
        return playCountdown(1000);
    }

    public CompletableFuture<Void> playCountdown(long digitDurationMs) {
        return run(token -> {
            if (width < 3 || height < 5) playSimpleCountdown(token, digitDurationMs);
            else playScalableCountdown(token, digitDurationMs);
        });
    }

    public CompletableFuture<Void> playWinAnimation() {
        return playWinAnimation(WinAnimationType.RADIAL_BURST);
    }

    public CompletableFuture<Void> playWinAnimation(WinAnimationType type) {
        return run(token -> {
            switch (type) {
                case RADIAL_BURST -> playRadialBurst(token);
                case RAINBOW_SWEEP -> playRainbowSweep(token);
                case SPARKLE -> playSparkle(token);
                case FIREWORKS -> playFireworks(token);
            }
        });
    }

    public CompletableFuture<Void> playLoseAnimation() {
        return playLoseAnimation(LoseAnimationType.FADE_TO_RED);
    }

    public CompletableFuture<Void> playLoseAnimation(LoseAnimationType type) {
        return run(token -> {
            switch (type) {
                case FADE_TO_RED -> playFadeToRed(token);
                case DESCENDING_CURTAIN -> playDescendingCurtain(token);
                case CRUMBLE -> playCrumble(token);
                case PULSE_RED -> playPulseRed(token);
            }
        });
    }

    public CompletableFuture<Void> playStandbyAnimation() {
        return playStandbyAnimation(StandbyAnimationType.BREATHING);
    }

    public CompletableFuture<Void> playStandbyAnimation(StandbyAnimationType type) {
        return run(token -> {
            switch (type) {
                case BREATHING -> playBreathing(token);
                case CORNER_PULSE -> playCornerPulse(token);
                case WAVE_BORDER -> playWaveBorder(token);
                case RANDOM_TWINKLE -> playRandomTwinkle(token);
            }
        });
    }

    private void playSimpleCountdown(RunToken token, long digitDurationMs) {
        TileColor[] colors = {TileColor.RED, TileColor.YELLOW, TileColor.GREEN};
        for (int i = 3; i > 0 && token.sleep(0); i--) {
            boardPublisher.accept(new Board<>(width, height, colors[3 - i]));
            if (!token.sleep(digitDurationMs)) return;
        }
        clearBoard();
    }

    private void playScalableCountdown(RunToken token, long digitDurationMs) {
        for (int digit = 3; digit >= 1; digit--) {
            boardPublisher.accept(renderDigit(digit));
            if (!token.sleep(digitDurationMs)) return;
        }
        for (int i = 0; i < 3; i++) {
            boardPublisher.accept(new Board<>(width, height, TileColor.GREEN));
            if (!token.sleep(150)) return;
            clearBoard();
            if (!token.sleep(150)) return;
        }
    }

    private Board<TileColor> renderDigit(int digit) {
        Board<TileColor> board = new Board<>(width, height, TileColor.OFF);
        boolean[][] pattern = getDigitPattern(digit);
        int startRow = (height - pattern.length) / 2;
        int startCol = (width - pattern[0].length) / 2;
        TileColor color = switch (digit) {
            case 3 -> TileColor.RED;
            case 2 -> TileColor.YELLOW;
            case 1 -> TileColor.GREEN;
            default -> TileColor.WHITE;
        };
        for (int r = 0; r < pattern.length; r++) {
            for (int c = 0; c < pattern[0].length; c++) {
                if (pattern[r][c] && inBounds(startRow + r, startCol + c)) {
                    board.set(startRow + r, startCol + c, color);
                }
            }
        }
        return board;
    }

    private boolean inBounds(int r, int c) {
        return r >= 0 && r < height && c >= 0 && c < width;
    }

    private boolean[][] getDigitPattern(int digit) {
        return switch (digit) {
            case 1 ->
                    new boolean[][]{{false, true, false}, {true, true, false}, {false, true, false}, {false, true, false}, {true, true, true}};
            case 2 ->
                    new boolean[][]{{true, true, true}, {false, false, true}, {true, true, true}, {true, false, false}, {true, true, true}};
            case 3 ->
                    new boolean[][]{{true, true, true}, {false, false, true}, {true, true, true}, {false, false, true}, {true, true, true}};
            default -> new boolean[5][3];
        };
    }

    private void playRadialBurst(RunToken token) {
        Position center = new Position(height / 2, width / 2);
        TileColor[] colors = {TileColor.YELLOW, TileColor.GREEN, TileColor.BLUE, TileColor.PINK, TileColor.LIGHT_BLUE};
        int maxRadius = Math.max(Math.max(center.row(), height - 1 - center.row()),
                Math.max(center.col(), width - 1 - center.col())) + 2;

        for (int radius = 0; radius <= maxRadius; radius++) {
            token.show(ringBandBoard(center, colors[radius % colors.length], radius));
            token.pause(100);
        }
        token.pause(500);
        token.clear();
    }
    

    private void playRainbowSweep(RunToken token) {
        TileColor[] rainbow = {TileColor.RED, TileColor.YELLOW, TileColor.GREEN,
                TileColor.BLUE, TileColor.PINK};

        for (int sweep = 0; sweep < 2; sweep++) {
            for (int col = 0; col < width; col++) {
                Board<TileColor> board = new Board<>(width, height, TileColor.OFF);
                for (int c = 0; c <= col; c++) {
                    TileColor color = rainbow[(c + sweep * width) % rainbow.length];
                    for (int row = 0; row < height; row++) {
                        board.set(row, c, color);
                    }
                }
                token.show(board);
                token.pause(80);
            }
        }
        clearBoard();
    }

    private void playSparkle(RunToken token) {
        TileColor[] colors = {TileColor.YELLOW, TileColor.WHITE, TileColor.LIGHT_BLUE};
        for (int cycle = 0; cycle < 15; cycle++) {
            Board<TileColor> board = new Board<>(width, height, TileColor.OFF);
            int sparks = 5 + (cycle % 5);
            for (int i = 0; i < sparks; i++) {
                board.set(rng.nextInt(height), rng.nextInt(width), colors[rng.nextInt(colors.length)]);
            }
            boardPublisher.accept(board);
            if (!token.sleep(120)) return;
        }
        clearBoard();
    }

    private void playFireworks(RunToken token) {
        TileColor[] colors = {TileColor.RED, TileColor.YELLOW, TileColor.GREEN,
                TileColor.BLUE, TileColor.PINK, TileColor.LIGHT_BLUE};
        for (int firework = 0; firework < 3; firework++) {
            Position center = new Position(randomInterior(height), randomInterior(width));
            TileColor color = colors[rng.nextInt(colors.length)];

            for (int radius = 0; radius <= 3; radius++) {
                token.show(ringBoard(center, color, radius));
                token.pause(100);
            }
            token.pause(200);
        }
        token.clear();
    }

    /**
     * Random index in [1, size-2] when the board is big enough, otherwise anywhere in [0, size-1].
     */
    private int randomInterior(int size) {
        return size > 2 ? 1 + rng.nextInt(size - 2) : rng.nextInt(size);
    }

    private void playFadeToRed(RunToken token) {
        Board<TileColor> board = new Board<>(width, height, TileColor.OFF);
        for (int phase = 0; phase < 3; phase++) {
            for (int row = 0; row < height; row++) {
                for (int col = 0; col < width; col++) {
                    if (rng.nextDouble() < 0.3) board.set(row, col, TileColor.RED); // was Math.random()
                }
            }
            boardPublisher.accept(board.copy());
            if (!token.sleep(300)) return;
        }
        board.fill(TileColor.RED);
        boardPublisher.accept(board);
        if (token.sleep(1000)) clearBoard();
    }

    // ------------------------------------------------------------------ lose

    private void playDescendingCurtain(RunToken token) {
        for (int row = 0; row < height; row++) {
            Board<TileColor> board = new Board<>(width, height, TileColor.OFF);
            for (int r = 0; r <= row; r++) {
                for (int col = 0; col < width; col++) {
                    board.set(r, col, TileColor.RED);
                }
            }
            token.show(board);
            token.pause(200);
        }
        token.pause(500);
        clearBoard();
    }

    private void playCrumble(RunToken token) {
        Board<TileColor> board = new Board<>(width, height, TileColor.YELLOW);
        token.show(board.copy());
        token.pause(300);

        List<Position> positions = new ArrayList<>();
        for (int row = 0; row < height; row++)
            for (int col = 0; col < width; col++)
                positions.add(new Position(row, col));
        Collections.shuffle(positions, rng);

        for (Position pos : positions) {
            board.set(pos.row(), pos.col(), TileColor.RED);
            if (rng.nextDouble() < 0.2) {
                token.show(board.copy());
                token.pause(50);
            }
        }
        token.show(board.copy());
        token.pause(500);
        clearBoard();
    }

    private void playPulseRed(RunToken token) {
        for (int pulse = 0; pulse < 4; pulse++) {
            token.show(new Board<>(width, height, TileColor.RED));
            token.pause(200);
            clearBoard();
            token.pause(200);
        }
    }

    private void playBreathing(RunToken token) {
        TileColor[] breathColors = {TileColor.BLUE, TileColor.LIGHT_BLUE};
        for (int cycle = 0; !token.isCancelled(); cycle++) {
            Board<TileColor> board = new Board<>(width, height, TileColor.OFF);
            TileColor color = breathColors[cycle % 2];
            paintCorners(board, color);
            boardPublisher.accept(board);
            if (!token.sleep(500)) return;

            if (width >= 3 && height >= 3) {
                paintBorder(board, color);
                boardPublisher.accept(board);
                if (!token.sleep(500)) return;
            }
        }
    }

    // ------------------------------------------------------------------ standby (run until cancelled)

    private void paintCorners(Board<TileColor> board, TileColor color) {
        board.set(0, 0, color);
        if (width > 1) board.set(0, width - 1, color);
        if (height > 1) board.set(height - 1, 0, color);
        if (height > 1 && width > 1) board.set(height - 1, width - 1, color);
    }

    private void paintBorder(Board<TileColor> board, TileColor color) {
        for (int col = 0; col < width; col++) {
            board.set(0, col, color);
            board.set(height - 1, col, color);
        }
        for (int row = 0; row < height; row++) {
            board.set(row, 0, color);
            board.set(row, width - 1, color);
        }
    }

    private void playCornerPulse(RunToken token) {
        TileColor[] colors = {TileColor.GREEN, TileColor.BLUE, TileColor.PINK, TileColor.YELLOW};

        for (int cycle = 0; ; cycle = (cycle + 1) % colors.length) {
            Board<TileColor> board = new Board<>(width, height, TileColor.OFF);
            TileColor color = colors[cycle];

            int row = (cycle / 2) * (height - 1);
            int col = (cycle % 2) * (width - 1);

            // 3x3 block around the corner, clipped to the board
            for (int dr = -1; dr <= 1; dr++) {
                for (int dc = -1; dc <= 1; dc++) {
                    int nr = row + dr;
                    int nc = col + dc;
                    if (nr >= 0 && nr < height && nc >= 0 && nc < width) {
                        board.set(nr, nc, color);
                    }
                }
            }
            token.show(board);
            token.pause(300);
        }
    }

    private void playWaveBorder(RunToken token) {
        TileColor color = TileColor.LIGHT_BLUE;

        // the pattern has period 3, so the offset just wraps (no overflow on endless runs)
        for (int offset = 0; ; offset = (offset + 1) % 3) {
            Board<TileColor> board = new Board<>(width, height, TileColor.OFF);

            for (int col = 0; col < width; col++) {
                if ((col + offset) % 3 == 0) board.set(0, col, color);
            }
            if (height > 1) {
                for (int col = 0; col < width; col++) {
                    if ((col + offset + 1) % 3 == 0) board.set(height - 1, col, color);
                }
            }
            for (int row = 0; row < height; row++) {
                if ((row + offset) % 3 == 0) board.set(row, 0, color);
            }
            if (width > 1) {
                for (int row = 0; row < height; row++) {
                    if ((row + offset + 1) % 3 == 0) board.set(row, width - 1, color);
                }
            }
            token.show(board);
            token.pause(200);
        }
    }

    private void playRandomTwinkle(RunToken token) {
        while (token.sleep(300)) { // runs forever, ~300ms per frame, until cancelCurrent() is called
            Board<TileColor> board = new Board<>(width, height, TileColor.OFF);
            int twinkles = 2 + rng.nextInt(3);
            for (int i = 0; i < twinkles; i++) board.set(rng.nextInt(height), rng.nextInt(width), TileColor.WHITE);
            boardPublisher.accept(board);
        }
    }


    public void shutdown() {
        cancelCurrent();
        executor.shutdownNow();
        try {
            executor.awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }


    public enum WinAnimationType {RADIAL_BURST, RAINBOW_SWEEP, SPARKLE, FIREWORKS}

    public enum LoseAnimationType {FADE_TO_RED, DESCENDING_CURTAIN, CRUMBLE, PULSE_RED}

    public enum StandbyAnimationType {BREATHING, CORNER_PULSE, WAVE_BORDER, RANDOM_TWINKLE}

    /**
     * Thrown internally to unwind a superseded animation cooperatively. Never leaves this class.
     */
    private static final class AnimationCancelledException extends RuntimeException {
        AnimationCancelledException() {
            super(null, null, false, false); // no stacktrace, cheap
        }
    }

    /**
     * A cooperative cancellation token bound to one "generation" of animation.
     */
    public final class RunToken {
        private final long myGeneration;

        RunToken(long myGeneration) {
            this.myGeneration = myGeneration;
        }

        boolean isCancelled() {
            return generation.get() != myGeneration;
        }

        /**
         * Sleeps, returning {@code false} immediately (without sleeping) if cancelled.
         */
        boolean sleep(long ms) {
            if (isCancelled()) return false;
            if (ms > 0) {
                try {
                    Thread.sleep(ms);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return !isCancelled();
        }

        /**
         * Like {@link #sleep} but unwinds the animation when cancelled.
         */
        void pause(long ms) {
            if (!sleep(ms)) throw new AnimationCancelledException();
        }

        /**
         * Publishes a frame unless this animation has been superseded.
         */
        void show(Board<TileColor> board) {
            if (isCancelled()) throw new AnimationCancelledException();
            boardPublisher.accept(board);
        }

        void clear() {
            show(new Board<>(width, height, TileColor.OFF));
        }
    }
}