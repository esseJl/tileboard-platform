package com.tileboard.app.settings;

/**
 * Wraps (de)serialization failures so callers see one exception type regardless of backing store.
 */
public class SettingsPersistenceException extends RuntimeException {
    public SettingsPersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}