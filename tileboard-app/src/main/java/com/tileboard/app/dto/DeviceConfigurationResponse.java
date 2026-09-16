package com.tileboard.app.dto;

import com.tileboard.app.service.device.DeviceConfiguration;

/** Read-facing view of {@link DeviceConfiguration}. */
public record DeviceConfigurationResponse(int width, int height, int tileCount) {

    public static DeviceConfigurationResponse from(DeviceConfiguration configuration) {
        return new DeviceConfigurationResponse(
                configuration.width(), configuration.height(), configuration.tileCount());
    }
}
