package com.tileboard.engine.feature;

import com.tileboard.engine.model.TileColor;
import com.tileboard.serial.board.Board;
import com.tileboard.serial.board.Position;

import java.util.List;
import java.util.function.Consumer;

/**
 * Generates visual "wave" effects on the board: ripple, cascade, sweep.
 * Each method returns immediately; the animation is driven by the caller's
 * tick loop or a background thread.
 *
 * <p>Every step sends a board snapshot via {@code boardPublisher} —
 * typically {@link com.tileboard.engine.core.GameContext#publishBoard}.
 */
public final class WaveGenerator {

    private final int                    width;
    private final int                    height;
    private final Consumer<Board<TileColor>> boardPublisher;

    public WaveGenerator(int width, int height, Consumer<Board<TileColor>> boardPublisher) {
        this.width          = width;
        this.height         = height;
        this.boardPublisher = boardPublisher;
    }

    /**
     * Sweeps a color row by row from top to bottom, with {@code delayMs}
     * between each row. Blocking – run on a background thread if needed.
     */
    public void sweepDown(TileColor color, long delayMs) {
        Board<TileColor> board = new Board<>(width, height, TileColor.OFF);
        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) board.set(row, col, color);
            boardPublisher.accept(board.copy());
            sleep(delayMs);
        }
    }

    /**
     * Expands a ripple from {@code center} outward, painting each "ring"
     * with {@code color}.
     */
    public void ripple(Position center, TileColor color, long delayMs) {
        int maxRadius = Math.max(
                Math.max(center.row(), height - 1 - center.row()),
                Math.max(center.col(), width  - 1 - center.col())
        );
        Board<TileColor> board = new Board<>(width, height, TileColor.OFF);
        for (int radius = 0; radius <= maxRadius; radius++) {
            final int r = radius;
            board.forEach((row, col, tile) -> {
                int dist = Math.max(Math.abs(row - center.row()), Math.abs(col - center.col()));
                if (dist == r) board.set(row, col, color);
            });
            boardPublisher.accept(board.copy());
            sleep(delayMs);
        }
    }

    /** Blinks the entire board between {@code on} and {@code off} for {@code times} cycles. */
    public void blink(TileColor on, TileColor off, int times, long intervalMs) {
        Board<TileColor> onBoard  = new Board<>(width, height, on);
        Board<TileColor> offBoard = new Board<>(width, height, off);
        for (int i = 0; i < times; i++) {
            boardPublisher.accept(onBoard.copy());
            sleep(intervalMs);
            boardPublisher.accept(offBoard.copy());
            sleep(intervalMs);
        }
    }

    private void sleep(long ms) {
        if (ms <= 0) return;
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}