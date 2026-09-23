package com.tileboard.app.settings;

import java.util.Optional;

/**
 * Generic, typed persistence for application settings.
 *
 * <p>The single seam between "the application has some configurable state" and "that
 * state survives a restart / is shared across instances". Device geometry today,
 * serial port assignment today, anything added tomorrow all flow through this one
 * interface keyed by a {@link SettingKey}, so:
 * <ul>
 *   <li>no caller hand-rolls JSON (de)serialization or a bespoke table/column,</li>
 *   <li>swapping the backing store (JPA today, Redis/etcd tomorrow) is a single-class change,</li>
 *   <li>nothing is hard-coded: keys, defaults and types are all supplied by the caller.</li>
 * </ul>
 */
public interface SettingsService {

    /**
     * The persisted value for {@code key}, if one has ever been {@link #set}.
     */
    <T> Optional<T> get(SettingKey<T> key);

    /**
     * {@link #get(SettingKey)}, falling back to {@link SettingKey#defaultValue()} when absent.
     */
    <T> T getOrDefault(SettingKey<T> key);

    /**
     * Persists {@code value} as the current value of {@code key}, replacing any previous value.
     */
    <T> void set(SettingKey<T> key, T value);

    /**
     * Removes any persisted value for {@code key}; subsequent reads behave as if never set.
     */
    void clear(SettingKey<?> key);

    /**
     * Whether {@code key} currently has a persisted value.
     */
    boolean isSet(SettingKey<?> key);
}