package com.tileboard.app.device;

import java.util.Optional;

/**
 * Owns the current {@link DeviceConfiguration}.
 *
 * <p>Deliberately storage-agnostic: this stage of the project only needs one
 * device configuration to exist in memory for the app to be useful end to
 * end. Swapping {@link InMemoryDeviceConfigurationService} for a
 * database-backed implementation later is a one-class change - no caller of
 * this interface needs to know or care.
 */
public interface DeviceConfigurationService {

    Optional<DeviceConfiguration> current();

    DeviceConfiguration configure(int width, int height);

    default boolean isConfigured() {
        return current().isPresent();
    }
}
