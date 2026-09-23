package com.tileboard.engine.model;


import com.tileboard.serial.board.Position;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable record of a single tile interaction arriving from the hardware.
 */
public record TileEvent(Position position, TileEventType type,
                        Instant occurredAt, String sessionId) {
    public TileEvent {
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(sessionId, "sessionId");
    }

    public static TileEvent touch(Position position, String sessionId) {
        return new TileEvent(position, TileEventType.TOUCH, Instant.now(), sessionId);
    }

    public static TileEvent release(Position position, String sessionId) {
        return new TileEvent(position, TileEventType.RELEASE, Instant.now(), sessionId);
    }
}