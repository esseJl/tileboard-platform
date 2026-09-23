package com.tileboard.app.settings;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tileboard.app.config.CacheConfig;
import com.tileboard.app.settings.persistence.ApplicationSetting;
import com.tileboard.app.settings.persistence.SettingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * {@link SettingsService} backed by a single generic table ({@link ApplicationSetting}), with
 * values (de)serialized to JSON via Jackson. This is the production default - see
 * {@code tileboard.settings.store} - because unlike {@link InMemorySettingsService},
 * values survive an application restart.
 *
 * <p>Reads go through a Caffeine-backed cache (see {@code CacheConfig}) since settings
 * are read far more often than written; writes evict the affected key so the very next
 * read is always consistent with what was just persisted.
 */
@Service
@ConditionalOnProperty(prefix = "tileboard.settings", name = "store", havingValue = "jpa", matchIfMissing = true)
public class JpaSettingsService implements SettingsService {

    private static final Logger log = LoggerFactory.getLogger(JpaSettingsService.class);

    private final SettingRepository repository;
    private final ObjectMapper objectMapper;
    private final Cache cache;

    public JpaSettingsService(SettingRepository repository,
                              ObjectMapper settingsObjectMapper,
                              CacheManager cacheManager) {
        this.repository = repository;
        this.objectMapper = settingsObjectMapper;
        this.cache = cacheManager.getCache(CacheConfig.SETTINGS_CACHE);
        if (this.cache == null) {
            throw new IllegalStateException("Cache '" + CacheConfig.SETTINGS_CACHE + "' is not configured");
        }
    }

    @Override
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public <T> Optional<T> get(SettingKey<T> key) {
        Cache.ValueWrapper cached = cache.get(key.id());
        if (cached != null) {
            return (Optional<T>) cached.get();
        }
        Optional<T> loaded = repository.findById(key.id())
                .map(entity -> deserialize(key, entity.getValue()));
        cache.put(key.id(), loaded);
        return loaded;
    }

    @Override
    public <T> T getOrDefault(SettingKey<T> key) {
        return get(key).orElse(key.defaultValue());
    }

    @Override
    @Transactional
    public <T> void set(SettingKey<T> key, T value) {
        String json = serialize(key, value);
        try {
            persist(key.id(), json);
        } catch (OptimisticLockingFailureException e) {
            // A concurrent writer won the race; last-write-wins is an acceptable trade-off
            // for admin-driven settings, so we retry once against the fresh row instead of
            // surfacing a 500 for what is really a benign race.
            log.warn("Concurrent update detected for setting '{}', retrying once", key.id());
            persist(key.id(), json);
        } finally {
            cache.evict(key.id());
        }
    }

    private void persist(String keyId, String json) {
        ApplicationSetting entity = repository.findById(keyId)
                .orElseGet(() -> new ApplicationSetting(keyId, json, Instant.now()));
        entity.setValue(json);
        entity.setUpdatedAt(Instant.now());
        repository.save(entity);
    }

    @Override
    @Transactional
    public void clear(SettingKey<?> key) {
        repository.deleteById(key.id());
        cache.evict(key.id());
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isSet(SettingKey<?> key) {
        return repository.existsById(key.id());
    }

    private <T> String serialize(SettingKey<T> key, T value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new SettingsPersistenceException(
                    "Failed to serialize setting '" + key.id() + "' of type " + key.type(), e);
        }
    }

    private <T> T deserialize(SettingKey<T> key, String json) {
        try {
            return objectMapper.readValue(json, key.type());
        } catch (JsonProcessingException e) {
            throw new SettingsPersistenceException(
                    "Failed to deserialize setting '" + key.id() + "' of type " + key.type()
                            + " - stored value may be corrupt or from an incompatible version", e);
        }
    }
}