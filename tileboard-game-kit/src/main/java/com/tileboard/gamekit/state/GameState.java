package com.tileboard.gamekit.state;

/** Immutable snapshot suitable for REST/SSE/telemetry. */
public record GameState(
        Outcome outcome,
        long score,
        int health,
        int level,
        int combo,
        long touches) {
    public GameState {
        if (score < 0) throw new IllegalArgumentException("score must be >= 0");
        if (health < 0 || level < 1 || combo < 0 || touches < 0)
            throw new IllegalArgumentException("invalid game state");
    }
}
