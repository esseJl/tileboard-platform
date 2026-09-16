package com.tileboard.gamekit.multiplayer;

/**
 * Identifies one participant within a {@link PlayerRoster} - one physical
 * player in a two-player game, or one member of a team in group play. A
 * thin wrapper over a display label rather than a raw {@code String}, so
 * "player id" is a distinct, type-safe concept wherever it shows up (map
 * keys, method signatures) instead of an unlabeled string that could be
 * confused with any other identifier.
 */
public record PlayerId(String label) {

    public PlayerId {
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("label must not be blank");
        }
    }
}
