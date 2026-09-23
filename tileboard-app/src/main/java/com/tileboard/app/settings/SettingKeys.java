package com.tileboard.app.settings;

import com.tileboard.app.config.DeviceConfiguration;
import com.tileboard.app.service.serial.PortAssignment;

/**
 * Central, append-only registry of every setting the application persists.
 *
 * <p>This is the ONLY place a new persisted setting is declared. To add a future
 * setting (e.g. a display theme, notification preferences, calibration offsets):
 *
 * <pre>{@code
 * public static final SettingKey<MyNewSettings> MY_NEW_SETTING =
 *         SettingKey.of("my-feature.settings", MyNewSettings.class, MyNewSettings.defaults());
 * }</pre>
 * <p>
 * No JPA, controller, or migration code needs to change - {@link SettingsService}
 * already knows how to store/retrieve any type through this one table.
 */
public final class SettingKeys {

    /**
     * The board's physical geometry. No default: "unset" is a real, distinct state
     * (see {@code DeviceNotConfiguredException}), not "0x0".
     */
    public static final SettingKey<DeviceConfiguration> DEVICE_CONFIGURATION =
            SettingKey.of("device.configuration", DeviceConfiguration.class);

    /**
     * Which system serial ports are currently assigned to IN/OUT. Defaults to "nothing assigned".
     */
    public static final SettingKey<PortAssignment> SERIAL_PORT_ASSIGNMENT =
            SettingKey.of("serial.port-assignment", PortAssignment.class, PortAssignment.empty());

    private SettingKeys() {
    }
}