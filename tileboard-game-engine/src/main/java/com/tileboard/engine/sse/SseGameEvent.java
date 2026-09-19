package com.tileboard.engine.sse;

import com.tileboard.engine.core.SessionSnapshot;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * DTO serialised to JSON for SSE consumers. Kept separate from the internal
 * {@link com.tileboard.engine.event.GameEvent} so the SSE contract can evolve
 * independently of the internal bus.
 */
public record SseGameEvent(String sessionId, String gameId,
                           SseGameEventType type, SessionSnapshot data, Instant timestamp) {
    public SseGameEvent {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(gameId, "gameId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(timestamp, "timestamp");
    }
}