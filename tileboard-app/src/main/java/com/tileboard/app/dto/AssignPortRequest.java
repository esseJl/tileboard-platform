package com.tileboard.app.dto;

import jakarta.validation.constraints.NotBlank;

public record AssignPortRequest(@NotBlank(message = "portName must not be blank") String portName) {
}
