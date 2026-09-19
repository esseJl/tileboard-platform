package com.tileboard.engine.event;

import com.tileboard.engine.core.SessionSnapshot;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * An immutable engine-level event published on the {@link GameEventBus} and
 * forwarded to SSE subscribers.
 */
public record GameEvent(String id, GameEventType type, String sessionId, String gameId,
                        SessionSnapshot payload, Instant occurredAt) {
    public GameEvent {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(gameId, "gameId");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }

    public static GameEvent of(GameEventType type, String sessionId, String gameId, SessionSnapshot payload) {
        return new GameEvent(
                UUID.randomUUID().toString(), type, sessionId, gameId, payload, Instant.now());
    }
}