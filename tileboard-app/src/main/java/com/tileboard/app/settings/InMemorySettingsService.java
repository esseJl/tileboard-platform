package com.tileboard.app.settings;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pure in-memory {@link SettingsService} - no database required.
 *
 * <p>Enabled with {@code tileboard.settings.store=memory}; intended for local
 * development, demos and tests where standing up a database is unnecessary friction.
 * Unlike {@link JpaSettingsService} (the production default), values do NOT survive
 * a restart.
 */
@Service
@ConditionalOnProperty(prefix = "tileboard.settings", name = "store", havingValue = "memory")
public class InMemorySettingsService implements SettingsService {

    private final Map<String, Object> values = new ConcurrentHashMap<>();

    @Override
    @SuppressWarnings("unchecked")
    public <T> Optional<T> get(SettingKey<T> key) {
        return Optional.ofNullable((T) values.get(key.id()));
    }

    @Override
    public <T> T getOrDefault(SettingKey<T> key) {
        return get(key).orElse(key.defaultValue());
    }

    @Override
    public <T> void set(SettingKey<T> key, T value) {
        values.put(key.id(), value);
    }

    @Override
    public void clear(SettingKey<?> key) {
        values.remove(key.id());
    }

    @Override
    public boolean isSet(SettingKey<?> key) {
        return values.containsKey(key.id());
    }
}