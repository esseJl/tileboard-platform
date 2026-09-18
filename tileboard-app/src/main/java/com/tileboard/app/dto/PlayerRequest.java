package com.tileboard.app.dto;

import com.tileboard.engine.model.Player;
import com.tileboard.engine.model.PlayerRole;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** One participant supplied when starting a game session. */
public record PlayerRequest(
        @NotBlank(message = "name must not be blank") String name,
        @NotNull(message = "role must not be null") PlayerRole role
) {
    public Player toPlayer() {
        return Player.of(name, role);
    }
}
