package com.tileboard.engine.core;

import com.tileboard.engine.model.Player;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable summary of a finished game session.
 */
public record GameResult(String sessionId, String gameId, GameStatus finalStatus, List<Player> winners,
                         Map<String, Integer> scoreByPlayerId, Duration duration, Instant finishedAt) {
    public GameResult {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(gameId, "gameId");
        Objects.requireNonNull(finalStatus, "finalStatus");
        Objects.requireNonNull(winners, "winners");
        Objects.requireNonNull(scoreByPlayerId, "scoreByPlayerId");
        Objects.requireNonNull(duration, "duration");
        Objects.requireNonNull(finishedAt, "finishedAt");
        winners = List.copyOf(winners);
        scoreByPlayerId = Map.copyOf(scoreByPlayerId);
    }

    public boolean hasWinner() {
        return !winners.isEmpty();
    }
}