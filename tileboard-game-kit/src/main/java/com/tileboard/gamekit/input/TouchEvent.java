package com.tileboard.gamekit.input;

import com.tileboard.gamekit.model.TilePosition;
import java.time.Instant;
import java.util.Objects;

/** Raw immutable touch event received from the physical board. */
public record TouchEvent(TilePosition position, Instant timestamp, String playerId) {
    public TouchEvent {
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(timestamp, "timestamp");
        playerId = playerId == null || playerId.isBlank() ? "default" : playerId;
    }

    public static TouchEvent now(TilePosition position) {
        return new TouchEvent(position, Instant.now(), "default");
    }
}
