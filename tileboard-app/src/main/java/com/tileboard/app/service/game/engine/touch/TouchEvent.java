package com.tileboard.app.service.game.engine.touch;

import com.tileboard.serial.board.Position;

import java.time.Instant;
import java.util.Objects;

/**
 * A single tile press recorded by the platform.
 */
public record TouchEvent(
        Position position,
        Instant timestamp,
        long sequence
) {
    public TouchEvent {
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(timestamp, "timestamp");
    }
}
