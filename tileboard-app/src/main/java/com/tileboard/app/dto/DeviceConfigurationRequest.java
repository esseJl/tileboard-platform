package com.tileboard.app.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * Request body for configuring/updating the board's physical geometry.
 */
public record DeviceConfigurationRequest(

        @Min(value = 1, message = "width must be at least 1")
        @Max(value = 255, message = "width must be at most 255 (single wire byte)")
        int width,

        @Min(value = 1, message = "height must be at least 1")
        @Max(value = 255, message = "height must be at most 255 (single wire byte)")
        int height) {
}
