package com.tileboard.engine.feature;

import com.tileboard.engine.model.TileColor;
import com.tileboard.serial.board.Board;
import com.tileboard.serial.board.Position;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Generates visual "wave" effects on the board: ripple, cascade, sweep.
 * Each method returns immediately; the animation is driven by the caller's
 * tick loop or a background thread.
 *
 * <p>Every step sends a board snapshot via {@code boardPublisher} —
 * typically {@link com.tileboard.engine.core.GameContext#publishBoard}.
 */
public final class WaveGenerator implements AutoCloseable {

    private final int width;
    private final int height;
    private final Consumer<Board<TileColor>> boardPublisher;
    private final ExecutorService executor;
    private final AtomicLong generation = new AtomicLong(0);
    private final Object runLock = new Object();
    private volatile Future<?> currentTask;
    private volatile CompletableFuture<Void> currentResult;

    public WaveGenerator(int width, int height, Consumer<Board<TileColor>> boardPublisher) {
        this.width = width;
        this.height = height;
        this.boardPublisher = boardPublisher;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "tileboard-wave");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Sweeps a color row by row from top to bottom, with {@code delayMs}
     * between each row. Blocking – run on a background thread if needed.
     */
    public CompletableFuture<Void> sweepDown(TileColor color, long delayMs) {
        return run(token -> {
            Board<TileColor> board = new Board<>(width, height, TileColor.OFF);
            for (int row = 0; row < height; row++) {
                for (int col = 0; col < width; col++) board.set(row, col, color);
                if (!token.publish(board.copy())) return;
                if (!token.sleep(delayMs)) return;
            }
        });
    }

    /**
     * Expands a ripple from {@code center} outward, painting each "ring"
     * with {@code color}.
     */
    public CompletableFuture<Void> ripple(Position center, TileColor color, long delayMs) {
        return run(token -> {
            int maxRadius = Math.max(
                    Math.max(center.row(), height - 1 - center.row()),
                    Math.max(center.col(), width - 1 - center.col()));
            Board<TileColor> board = new Board<>(width, height, TileColor.OFF);
            for (int radius = 0; radius <= maxRadius; radius++) {
                final int r = radius;
                board.forEach((row, col, tile) -> {
                    int dist = Math.max(Math.abs(row - center.row()), Math.abs(col - center.col()));
                    if (dist == r) board.set(row, col, color);
                });
                if (!token.publish(board.copy())) return;
                if (!token.sleep(delayMs)) return;
            }
        });
    }

    /**
     * Blinks the entire board between {@code on} and {@code off} for {@code times} cycles.
     */
    public CompletableFuture<Void> blink(TileColor on, TileColor off, int times, long intervalMs) {
        return run(token -> {
            Board<TileColor> onBoard = new Board<>(width, height, on);
            Board<TileColor> offBoard = new Board<>(width, height, off);
            for (int i = 0; i < times; i++) {
                if (!token.publish(onBoard.copy())) return;
                if (!token.sleep(intervalMs)) return;
                if (!token.publish(offBoard.copy())) return;
                if (!token.sleep(intervalMs)) return;
            }
        });
    }


    public void cancelCurrent() {
        synchronized (runLock) {
            generation.incrementAndGet();
            Future<?> task = currentTask;
            if (task != null) task.cancel(true);
            CompletableFuture<Void> res = currentResult;
            if (res != null) res.cancel(false);
            currentTask = null;
            currentResult = null;
        }
    }

    private CompletableFuture<Void> run(Consumer<WaveToken> body) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        synchronized (runLock) {
            Future<?> previousTask = currentTask;
            CompletableFuture<Void> previousResult = currentResult;
            if (previousTask != null) {
                previousTask.cancel(true);
            }
            if (previousResult != null) {
                previousResult.cancel(false);
            }
            long myGeneration = generation.incrementAndGet();
            WaveToken token = new WaveToken(myGeneration);
            currentResult = result;
            try {
                Future<?> submitted = executor.submit(() -> {
                    try {
                        body.accept(token);
                        result.complete(null);
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

    @Override
    public void close() {
        cancelCurrent();
        executor.shutdownNow();
        try {
            executor.awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private final class WaveToken {
        private final long myGeneration;

        WaveToken(long myGeneration) {
            this.myGeneration = myGeneration;
        }

        private boolean isCancelled() {
            return generation.get() != myGeneration;
        }

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

        boolean publish(Board<TileColor> board) {
            if (isCancelled()) return false;
            boardPublisher.accept(board);
            return true;
        }
    }
}