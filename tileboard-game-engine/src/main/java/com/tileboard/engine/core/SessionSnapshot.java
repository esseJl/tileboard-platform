package com.tileboard.engine.core;

import com.tileboard.engine.feature.HealthSystem;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * DTO serialised to JSON for SSE consumers. Kept separate from the internal
 * {@link com.tileboard.engine.event.GameEvent} so the SSE contract can evolve
 * independently of the internal bus.
 *
 * <p>Feature-derived fields ({@code remainingSeconds}, {@code health},
 * {@code recentTouches}, {@code combo}, {@code reaction}, {@code report}) are
 * best-effort: a session that doesn't use a given feature (e.g. no countdown
 * ever started) simply reports the field's "empty" value ({@code null}, an
 * empty map/list, or a zeroed record) rather than omitting it, so consumers
 * can always deserialise the same shape.
 *
 * <p>All collections are defensively copied into unmodifiable views by the
 * compact constructor, so a snapshot is safe to hand to other threads (event
 * subscribers, JSON serialisers, ...) even if the caller keeps mutating the
 * collections it originally passed in.
 */
public record SessionSnapshot(
        Map<String, Integer> scores,
        int level,
        String status,
        long elapsedSeconds,
        List<List<String>> board,
        Long remainingSeconds,
        Long countdownTotalSeconds,
        Map<String, HealthSystem.Status> health,
        List<TouchInfo> recentTouches,
        long totalTouches,
        ComboInfo combo,
        ReactionInfo reaction,
        GameResult report) {

    public SessionSnapshot {
        Objects.requireNonNull(scores, "scores");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(board, "board");
        Objects.requireNonNull(health, "health");
        Objects.requireNonNull(recentTouches, "recentTouches");
        Objects.requireNonNull(combo, "combo");
        Objects.requireNonNull(reaction, "reaction");
        // remainingSeconds, countdownTotalSeconds and report are intentionally
        // nullable: they mean "not applicable right now" (no countdown /
        // session still in progress), not "unknown".
        scores = Map.copyOf(scores);
        board = board.stream().map(List::copyOf).toList();
        health = Map.copyOf(health);
        recentTouches = List.copyOf(recentTouches);
    }

    /**
     * Legacy 4-arg constructor kept for backward compatibility with existing
     * callers/tests. All feature-derived fields are set to their empty state
     * and {@code board} is empty.
     */
    public SessionSnapshot(Map<String, Integer> scores, int level, String status, long elapsedSeconds) {
        this(scores, level, status, elapsedSeconds, List.of());
    }

    /**
     * Legacy 5-arg constructor kept for backward compatibility with existing
     * callers/tests. All feature-derived fields are set to their empty state.
     */
    public SessionSnapshot(Map<String, Integer> scores, int level, String status, long elapsedSeconds,
                            List<List<String>> board) {
        this(scores, level, status, elapsedSeconds, board, null, null, Map.of(), List.of(), 0L,
                ComboInfo.EMPTY, ReactionInfo.EMPTY, null);
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * One recorded tile interaction, as exposed newest-first via
     * {@link #recentTouches()}.
     */
    public record TouchInfo(int row, int col, String eventType, Instant occurredAt) {
        public TouchInfo {
            Objects.requireNonNull(eventType, "eventType");
            Objects.requireNonNull(occurredAt, "occurredAt");
        }
    }

    /**
     * Session-wide (not per-player) combo state. {@link com.tileboard.engine.feature.ComboTracker}
     * does not currently track combos per player, so neither does this snapshot.
     */
    public record ComboInfo(int current, int max) {
        public static final ComboInfo EMPTY = new ComboInfo(0, 0);
    }

    /**
     * Session-wide (not per-player) reaction-speed stats, mirroring
     * {@link com.tileboard.engine.feature.ReactionSpeedTracker}. All millisecond
     * fields are {@code null} and {@code count} is {@code 0} until the first
     * reaction is recorded.
     */
    public record ReactionInfo(Double lastMs, Double bestMs, Double averageMs, long count) {
        public static final ReactionInfo EMPTY = new ReactionInfo(null, null, null, 0L);
    }

    /**
     * Builds a {@link SessionSnapshot} step by step. Every feature-derived
     * field defaults to its "empty"/not-applicable state, so a caller only
     * needs to set what it actually has data for.
     */
    public static final class Builder {
        private Map<String, Integer> scores = Map.of();
        private int level = 1;
        private String status = "";
        private long elapsedSeconds;
        private List<List<String>> board = List.of();
        private Long remainingSeconds;
        private Long countdownTotalSeconds;
        private Map<String, HealthSystem.Status> health = Map.of();
        private List<TouchInfo> recentTouches = List.of();
        private long totalTouches;
        private ComboInfo combo = ComboInfo.EMPTY;
        private ReactionInfo reaction = ReactionInfo.EMPTY;
        private GameResult report;

        private Builder() {
        }

        public Builder scores(Map<String, Integer> scores) {
            this.scores = Objects.requireNonNull(scores, "scores");
            return this;
        }

        public Builder level(int level) {
            this.level = level;
            return this;
        }

        public Builder status(String status) {
            this.status = Objects.requireNonNull(status, "status");
            return this;
        }

        public Builder elapsedSeconds(long elapsedSeconds) {
            this.elapsedSeconds = elapsedSeconds;
            return this;
        }

        public Builder board(List<List<String>> board) {
            this.board = Objects.requireNonNull(board, "board");
            return this;
        }

        /** {@code null} means no countdown is currently active. */
        public Builder remainingSeconds(Long remainingSeconds) {
            this.remainingSeconds = remainingSeconds;
            return this;
        }

        /** {@code null} means no countdown is currently active. */
        public Builder countdownTotalSeconds(Long countdownTotalSeconds) {
            this.countdownTotalSeconds = countdownTotalSeconds;
            return this;
        }

        public Builder health(Map<String, HealthSystem.Status> health) {
            this.health = Objects.requireNonNull(health, "health");
            return this;
        }

        /** Expected newest-first; see {@link SessionSnapshot#recentTouches()}. */
        public Builder recentTouches(List<TouchInfo> recentTouches) {
            this.recentTouches = Objects.requireNonNull(recentTouches, "recentTouches");
            return this;
        }

        public Builder totalTouches(long totalTouches) {
            this.totalTouches = totalTouches;
            return this;
        }

        public Builder combo(ComboInfo combo) {
            this.combo = Objects.requireNonNull(combo, "combo");
            return this;
        }

        public Builder reaction(ReactionInfo reaction) {
            this.reaction = Objects.requireNonNull(reaction, "reaction");
            return this;
        }

        /** {@code null} while the session is still in progress. */
        public Builder report(GameResult report) {
            this.report = report;
            return this;
        }

        public SessionSnapshot build() {
            return new SessionSnapshot(scores, level, status, elapsedSeconds, board, remainingSeconds,
                    countdownTotalSeconds, health, recentTouches, totalTouches, combo, reaction, report);
        }
    }
}
