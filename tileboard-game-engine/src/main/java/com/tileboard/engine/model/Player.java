package com.tileboard.engine.model;

import java.util.Objects;
import java.util.UUID;

/**
 * Represents a participant in a game session.
 */
public record Player(String id, String name, PlayerRole role) {

    public Player {
        Objects.requireNonNull(id,   "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(role, "role");
    }

    public static Player of(String name, PlayerRole role) {
        return new Player(UUID.randomUUID().toString(), name, role);
    }

    public static Player solo(String name) {
        return of(name, PlayerRole.SOLO);
    }
}