package com.tileboard.engine.core;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable, JSON-friendly snapshot attached to every {@code GameEvent}
 * published on the {@code GameEventBus} (and, from there, forwarded as-is
 * to SSE subscribers by {@code GameEventSseEmitter}).
 *
 * @param board row-major grid of human-readable tile names (e.g. {@code "RED"},
 *              {@code "OFF"} — the {@link com.tileboard.engine.model.TileColor}
 *              enum constant name), so a person reading the raw JSON can see
 *              exactly what the physical board looks like without decoding any
 *              wire protocol. Never {@code null}; empty ({@code List.of()}) only
 *              for snapshots built before a board size was known.
 */
public record SessionSnapshot(Map<String, Integer> scores, int level, String status, long elapsedSeconds,
                              List<List<String>> board) {

    public SessionSnapshot {
        Objects.requireNonNull(scores, "scores");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(board, "board");
    }

    /**
     * Backward-compatible constructor for existing callers/tests that don't have
     * board data available (or don't care about it) — defaults {@code board} to empty.
     */
    public SessionSnapshot(Map<String, Integer> scores, int level, String status, long elapsedSeconds) {
        this(scores, level, status, elapsedSeconds, List.of());
    }
}
