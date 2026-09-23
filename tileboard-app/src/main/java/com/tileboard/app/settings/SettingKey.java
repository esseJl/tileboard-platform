package com.tileboard.app.settings;

import java.util.Objects;

/**
 * A strongly-typed, stable identifier for a single piece of persisted application
 * configuration (device geometry, serial port assignment, or any setting introduced
 * in the future).
 *
 * <p>Deliberately NOT an enum: every new setting the application ever needs is added
 * by declaring one more {@code public static final SettingKey<...>} constant in
 * {@link SettingKeys} - no switch statement, no persistence code, no controller code
 * has to change. {@link SettingsService} is generic over {@link #type()}, so adding a
 * setting is a one-line, purely additive change.
 *
 * @param id           stable key used as the row id in storage - treat it like a wire/DB
 *                     contract: safe to add new ids, never rename or reuse one.
 * @param type         the Java type this setting (de)serializes to/from
 * @param defaultValue value handed back by {@link SettingsService#getOrDefault(SettingKey)}
 *                     when nothing has been persisted yet; may be {@code null} if the
 *                     setting has no sane default (e.g. device geometry).
 */
public record SettingKey<T>(String id, Class<T> type, T defaultValue) {

    public SettingKey {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(type, "type must not be null");
        if (id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
    }

    /**
     * For settings with no meaningful default - absence is a distinct, valid state.
     */
    public static <T> SettingKey<T> of(String id, Class<T> type) {
        return new SettingKey<>(id, type, null);
    }

    public static <T> SettingKey<T> of(String id, Class<T> type, T defaultValue) {
        return new SettingKey<>(id, type, defaultValue);
    }
}
