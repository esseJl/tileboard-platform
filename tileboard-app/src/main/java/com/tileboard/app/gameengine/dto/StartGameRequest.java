package com.tileboard.app.gameengine.dto;

import com.tileboard.app.gameengine.GameMode;
import jakarta.validation.constraints.NotNull;

public record StartGameRequest(@NotNull(message = "mode is required") GameMode mode) {
}
