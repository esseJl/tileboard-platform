package com.tileboard.app.serial.dto;

import jakarta.validation.constraints.NotBlank;

public record AssignPortRequest(@NotBlank(message = "portName must not be blank") String portName) {
}
