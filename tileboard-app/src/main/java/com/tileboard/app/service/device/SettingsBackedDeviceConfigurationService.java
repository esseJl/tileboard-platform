package com.tileboard.app.service.device;

import com.tileboard.app.config.DeviceConfiguration;
import com.tileboard.app.settings.SettingKeys;
import com.tileboard.app.settings.SettingsService;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * {@link DeviceConfigurationService} backed by {@link SettingsService}, so the board's
 * geometry survives an application restart and is stored through the same generic
 * settings mechanism used for every other piece of configuration in the app.
 *
 * <p>Note this class knows NOTHING about JPA, JSON, or caching - swapping
 * {@link SettingsService}'s backing store (see {@code tileboard.settings.store}) never
 * requires touching this class or any of its callers.
 */
@Service
public class SettingsBackedDeviceConfigurationService implements DeviceConfigurationService {

    private final SettingsService settingsService;

    public SettingsBackedDeviceConfigurationService(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @Override
    public Optional<DeviceConfiguration> current() {
        return settingsService.get(SettingKeys.DEVICE_CONFIGURATION);
    }

    @Override
    public DeviceConfiguration configure(int width, int height) {
        DeviceConfiguration configuration = new DeviceConfiguration(width, height);
        settingsService.set(SettingKeys.DEVICE_CONFIGURATION, configuration);
        return configuration;
    }
}