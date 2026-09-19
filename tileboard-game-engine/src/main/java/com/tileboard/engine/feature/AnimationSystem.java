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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Animation system for displaying different game states on the tileboard.
 * Includes countdown, win, lose, and standby animations.
 */
public final class AnimationSystem {

    private final int width;
    private final int height;
    private final Consumer<Board<TileColor>> boardPublisher;
    private final ExecutorService animationExecutor;
    private final AtomicBoolean cancelRequested = new AtomicBoolean(false);
    private final AtomicReference<CompletableFuture<Void>> currentAnimation = new AtomicReference<>();
    private final Random rng = new Random();
    private final AtomicLong generation = new AtomicLong(0);
    private final AtomicReference<Thread> runningThread = new AtomicReference<>();


    public AnimationSystem(int width, int height, Consumer<Board<TileColor>> boardPublisher) {
        this.width = width;
        this.height = height;
        this.boardPublisher = boardPublisher;
        this.animationExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "tileboard-animation");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Displays countdown animation before game start (3, 2, 1)
     */
    public CompletableFuture<Void> playCountdown() {
        return playCountdown(1000);
    }

    public CompletableFuture<Void> playCountdown(long digitDurationMs) {
        cancelCurrent();
        return submit(() -> {
            if (width < 3 || height < 5) {
                playSimpleCountdown(digitDurationMs);
            } else {
                playScalableCountdown(digitDurationMs);
            }
        });
    }

    /**
     * Win animation - colorful wave pattern from center outward
     */
    public CompletableFuture<Void> playWinAnimation() {
        return playWinAnimation(WinAnimationType.RADIAL_BURST);
    }

    public CompletableFuture<Void> playWinAnimation(WinAnimationType type) {
        cancelCurrent();
        return submit(() -> {
            switch (type) {
                case RADIAL_BURST -> playRadialBurst();
                case RAINBOW_SWEEP -> playRainbowSweep();
                case SPARKLE -> playSparkle();
                case FIREWORKS -> playFireworks();
            }
        });
    }

    /**
     * Lose animation - gradual fade to red
     */
    public CompletableFuture<Void> playLoseAnimation() {
        return playLoseAnimation(LoseAnimationType.FADE_TO_RED);
    }

    public CompletableFuture<Void> playLoseAnimation(LoseAnimationType type) {
        cancelCurrent();
        return submit(() -> {
            switch (type) {
                case FADE_TO_RED -> playFadeToRed();
                case DESCENDING_CURTAIN -> playDescendingCurtain();
                case CRUMBLE -> playCrumble();
                case PULSE_RED -> playPulseRed();
            }
        });
    }

    /**
     * انیمیشن حالت آماده‌به‌کار - تنفس آرام
     * Standby animation - gentle breathing effect
     */
    public CompletableFuture<Void> playStandbyAnimation() {
        return playStandbyAnimation(StandbyAnimationType.BREATHING);
    }

    public CompletableFuture<Void> playStandbyAnimation(StandbyAnimationType type) {
        cancelCurrent();
        return submit(() -> {
            switch (type) {
                case BREATHING -> playBreathing();
                case CORNER_PULSE -> playCornerPulse();
                case WAVE_BORDER -> playWaveBorder();
                case RANDOM_TWINKLE -> playRandomTwinkle();
            }
        });
    }

    public void cancelCurrent() {
        generation.incrementAndGet();
        cancelRequested.set(true);
        Thread t = runningThread.getAndSet(null);
        if (t != null) t.interrupt();
    }


    public void shutdown() {
        cancelCurrent();
        animationExecutor.shutdownNow();
        try {
            animationExecutor.awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void playSimpleCountdown(long digitDurationMs) {
        TileColor[] colors = {TileColor.RED, TileColor.YELLOW, TileColor.GREEN};
        for (int i = 3; i > 0; i--) {
            Board<TileColor> board = new Board<>(width, height, colors[3 - i]);
            boardPublisher.accept(board);
            sleep(digitDurationMs);
        }
        clearBoard();
    }

    private void playScalableCountdown(long digitDurationMs) {
        for (int digit = 3; digit >= 1; digit--) {
            Board<TileColor> board = renderDigit(digit);
            boardPublisher.accept(board);
            sleep(digitDurationMs);
        }
        // فلش شروع / Start flash
        for (int i = 0; i < 3; i++) {
            boardPublisher.accept(new Board<>(width, height, TileColor.GREEN));
            sleep(150);
            clearBoard();
            sleep(150);
        }
    }

    private Board<TileColor> renderDigit(int digit) {
        Board<TileColor> board = new Board<>(width, height, TileColor.OFF);
        boolean[][] pattern = getDigitPattern(digit);

        int patternHeight = pattern.length;
        int patternWidth = pattern[0].length;

        // مرکز کردن عدد روی بورد / Center digit on board
        int startRow = (height - patternHeight) / 2;
        int startCol = (width - patternWidth) / 2;

        TileColor color = switch (digit) {
            case 3 -> TileColor.RED;
            case 2 -> TileColor.YELLOW;
            case 1 -> TileColor.GREEN;
            default -> TileColor.WHITE;
        };

        for (int r = 0; r < patternHeight; r++) {
            for (int c = 0; c < patternWidth; c++) {
                if (pattern[r][c] &&
                        startRow + r >= 0 && startRow + r < height &&
                        startCol + c >= 0 && startCol + c < width) {
                    board.set(startRow + r, startCol + c, color);
                }
            }
        }

        return board;
    }

    private boolean[][] getDigitPattern(int digit) {
        return switch (digit) {
            case 1 -> new boolean[][]{
                    {false, true, false},
                    {true, true, false},
                    {false, true, false},
                    {false, true, false},
                    {true, true, true}
            };
            case 2 -> new boolean[][]{
                    {true, true, true},
                    {false, false, true},
                    {true, true, true},
                    {true, false, false},
                    {true, true, true}
            };
            case 3 -> new boolean[][]{
                    {true, true, true},
                    {false, false, true},
                    {true, true, true},
                    {false, false, true},
                    {true, true, true}
            };
            default -> new boolean[5][3];
        };
    }


    private void playRadialBurst() {
        Position center = new Position(height / 2, width / 2);
        TileColor[] colors = {TileColor.YELLOW, TileColor.GREEN, TileColor.BLUE,
                TileColor.PINK, TileColor.LIGHT_BLUE};

        int maxRadius = Math.max(
                Math.max(center.row(), height - 1 - center.row()),
                Math.max(center.col(), width - 1 - center.col())
        ) + 2;

        for (int radius = 0; radius <= maxRadius; radius++) {
            Board<TileColor> board = createBoard(colors, radius, center);

            boardPublisher.accept(board);
            sleep(100);
        }

        sleep(500);
        clearBoard();
    }

    private Board<TileColor> createBoard(TileColor[] colors, int radius, Position center) {
        Board<TileColor> board = new Board<>(width, height, TileColor.OFF);
        TileColor color = colors[radius % colors.length];

        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {
                int dist = Math.max(
                        Math.abs(row - center.row()),
                        Math.abs(col - center.col())
                );
                if (dist <= radius && dist >= radius - 1) {
                    board.set(row, col, color);
                }
            }
        }
        return board;
    }

    private void playRainbowSweep() {
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
                boardPublisher.accept(board);
                sleep(80);
            }
        }
        clearBoard();
    }

    private void playSparkle() {
        TileColor[] colors = {TileColor.YELLOW, TileColor.WHITE, TileColor.LIGHT_BLUE};

        for (int cycle = 0; cycle < 15; cycle++) {
            Board<TileColor> board = new Board<>(width, height, TileColor.OFF);

            int sparks = 5 + (cycle % 5);
            for (int i = 0; i < sparks; i++) {
                int row = rng.nextInt(height);
                int col = rng.nextInt(width);
                TileColor color = colors[rng.nextInt(colors.length)];
                board.set(row, col, color);
            }

            boardPublisher.accept(board);
            sleep(120);
        }
        clearBoard();
    }

    private void playFireworks() {
        for (int firework = 0; firework < 3; firework++) {
            int centerRow = 1 + rng.nextInt(Math.max(1, height - 2));
            int centerCol = 1 + rng.nextInt(Math.max(1, width - 2));
            Position center = new Position(centerRow, centerCol);

            TileColor color = TileColor.values()[1 + rng.nextInt(6)];

            for (int radius = 0; radius <= 3; radius++) {
                Board<TileColor> board = new Board<>(width, height, TileColor.OFF);

                for (int row = 0; row < height; row++) {
                    for (int col = 0; col < width; col++) {
                        int dist = Math.max(
                                Math.abs(row - center.row()),
                                Math.abs(col - center.col())
                        );
                        if (dist == radius) {
                            board.set(row, col, color);
                        }
                    }
                }

                boardPublisher.accept(board);
                sleep(100);
            }
            sleep(200);
        }
        clearBoard();
    }


    private void playFadeToRed() {
        Board<TileColor> board = new Board<>(width, height, TileColor.OFF);

        for (int phase = 0; phase < 3; phase++) {
            for (int row = 0; row < height; row++) {
                for (int col = 0; col < width; col++) {
                    if (Math.random() < 0.3) {
                        board.set(row, col, TileColor.RED);
                    }
                }
            }
            boardPublisher.accept(board.copy());
            sleep(300);
        }

        board.fill(TileColor.RED);
        boardPublisher.accept(board);
        sleep(1000);
        clearBoard();
    }

    private void playDescendingCurtain() {
        for (int row = 0; row < height; row++) {
            Board<TileColor> board = new Board<>(width, height, TileColor.OFF);
            for (int r = 0; r <= row; r++) {
                for (int col = 0; col < width; col++) {
                    board.set(r, col, TileColor.RED);
                }
            }
            boardPublisher.accept(board);
            sleep(200);
        }
        sleep(500);
        clearBoard();
    }

    private void playCrumble() {
        Board<TileColor> board = new Board<>(width, height, TileColor.YELLOW);
        boardPublisher.accept(board);
        sleep(300);

        List<Position> positions = new ArrayList<>();
        for (int row = 0; row < height; row++)
            for (int col = 0; col < width; col++)
                positions.add(new Position(row, col));

        Collections.shuffle(positions, rng);          // FIXED: pass rng

        for (Position pos : positions) {
            board.set(pos.row(), pos.col(), TileColor.RED);
            if (rng.nextDouble() < 0.2) {             // FIXED: use field rng
                boardPublisher.accept(board.copy());
                sleep(50);
            }
        }
        boardPublisher.accept(board);
        sleep(500);
        clearBoard();
    }

    private void playPulseRed() {
        for (int pulse = 0; pulse < 4; pulse++) {
            boardPublisher.accept(new Board<>(width, height, TileColor.RED));
            sleep(200);
            clearBoard();
            sleep(200);
        }
    }

    private void playBreathing() {
        TileColor[] breathColors = {TileColor.BLUE, TileColor.LIGHT_BLUE};

        for (int cycle = 0; cycle < 20; cycle++) {
            // Phase 1: Corner only
            Board<TileColor> board = new Board<>(width, height, TileColor.OFF);
            TileColor color = breathColors[cycle % 2];

            if (height > 0 && width > 0) {
                board.set(0, 0, color);
                if (width > 1) board.set(0, width - 1, color);
                if (height > 1) board.set(height - 1, 0, color);
                if (height > 1 && width > 1) board.set(height - 1, width - 1, color);
            }

            boardPublisher.accept(board);
            sleep(500);

            // Phase 2: Expand
            if (width >= 3 && height >= 3) {
                for (int col = 0; col < width; col++) {
                    board.set(0, col, color);
                    board.set(height - 1, col, color);
                }
                for (int row = 0; row < height; row++) {
                    board.set(row, 0, color);
                    board.set(row, width - 1, color);
                }
                boardPublisher.accept(board);
                sleep(500);
            }
        }
    }

    private void playCornerPulse() {
        TileColor[] colors = {TileColor.GREEN, TileColor.BLUE, TileColor.PINK, TileColor.YELLOW};

        for (int cycle = 0; cycle < 20; cycle++) {
            Board<TileColor> board = new Board<>(width, height, TileColor.OFF);
            TileColor color = colors[cycle % colors.length];

            int corner = cycle % 4;
            int row = (corner / 2) * (height - 1);
            int col = (corner % 2) * (width - 1);

            if (row < height && col < width) {
                board.set(row, col, color);

                // اضافه کردن حلقه دور گوشه / Add ring around corner
                for (int dr = -1; dr <= 1; dr++) {
                    for (int dc = -1; dc <= 1; dc++) {
                        int nr = row + dr;
                        int nc = col + dc;
                        if (nr >= 0 && nr < height && nc >= 0 && nc < width) {
                            board.set(nr, nc, color);
                        }
                    }
                }
            }

            boardPublisher.accept(board);
            sleep(300);
        }
    }

    private void playWaveBorder() {
        for (int offset = 0; offset < width + height; offset++) {
            Board<TileColor> board = new Board<>(width, height, TileColor.OFF);
            TileColor color = TileColor.LIGHT_BLUE;

            for (int col = 0; col < width; col++) {
                if ((col + offset) % 3 == 0) {
                    board.set(0, col, color);
                }
            }

            if (height > 1) {
                for (int col = 0; col < width; col++) {
                    if ((col + offset + 1) % 3 == 0) {
                        board.set(height - 1, col, color);
                    }
                }
            }

            for (int row = 0; row < height; row++) {
                if ((row + offset) % 3 == 0) {
                    board.set(row, 0, color);
                }
            }

            if (width > 1) {
                for (int row = 0; row < height; row++) {
                    if ((row + offset + 1) % 3 == 0) {
                        board.set(row, width - 1, color);
                    }
                }
            }

            boardPublisher.accept(board);
            sleep(200);
        }
    }

    private void playRandomTwinkle() {
        for (int frame = 0; frame < 50; frame++) {
            Board<TileColor> board = new Board<>(width, height, TileColor.OFF);

            int twinkles = 2 + rng.nextInt(3);
            for (int i = 0; i < twinkles; i++) {
                int row = rng.nextInt(height);
                int col = rng.nextInt(width);
                board.set(row, col, TileColor.WHITE);
            }

            boardPublisher.accept(board);
            sleep(300);
        }
    }


    private void clearBoard() {
        boardPublisher.accept(new Board<>(width, height, TileColor.OFF));
    }

    /**
     * Submits an animation body, handling cooperative cancellation.
     */
    private CompletableFuture<Void> submit(Runnable body) {
        cancelRequested.set(false);
        //final long myGen = generation.get(); // keep for actual guard
        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
            runningThread.set(Thread.currentThread());
            try {
                body.run();
            } finally {
                runningThread.set(null);
                Thread.interrupted();
            }
        }, animationExecutor);
        currentAnimation.set(future);
        return future;
    }

    private void sleep(long ms) {
        // || generation.get() != myGen
        if (cancelRequested.get()) throw new AnimationCancelledException();
        if (ms > 0) {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AnimationCancelledException();
            }
        }
        if (cancelRequested.get()) throw new AnimationCancelledException();
    }

    public enum WinAnimationType {
        RADIAL_BURST,
        RAINBOW_SWEEP,
        SPARKLE,
        FIREWORKS
    }

    public enum LoseAnimationType {
        FADE_TO_RED,
        DESCENDING_CURTAIN,
        CRUMBLE,
        PULSE_RED
    }

    public enum StandbyAnimationType {
        BREATHING,
        CORNER_PULSE,
        WAVE_BORDER,
        RANDOM_TWINKLE
    }

    /**
     * Thrown internally to unwind a superseded animation cooperatively. Never leaves this class.
     */
    private static final class AnimationCancelledException extends RuntimeException {
        AnimationCancelledException() {
            super(null, null, false, false);
        } // no stacktrace, cheap
    }
}