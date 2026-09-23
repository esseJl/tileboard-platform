package com.tileboard.app.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record StartGameRequest(
        @NotBlank(message = "gameId must not be blank") String gameId,
        @NotEmpty(message = "at least one player is required") @Valid List<PlayerRequest> players) {
}
