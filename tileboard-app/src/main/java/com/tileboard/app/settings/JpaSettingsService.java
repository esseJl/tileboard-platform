package com.tileboard.app.settings;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tileboard.app.settings.persistence.ApplicationSetting;
import com.tileboard.app.settings.persistence.SettingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
 * <p>Reads go through {@link SettingRepository#findById}, which - since {@link ApplicationSetting}
 * is {@code @Cacheable} - is served from Hibernate's second-level cache after the first load
 * instead of hitting the database every time (see {@code application.yml} for the region-factory/
 * provider wiring). {@code set()}/{@code clear()} save/delete through the same repository, so
 * Hibernate evicts the L2C entry itself; no manual cache bookkeeping is needed here.
 *
 * <p>Note this means a setting that was never set is NOT negatively cached: every {@link #get}
 * for a still-unset key does hit the database (a cheap primary-key lookup that finds nothing),
 * unlike the previous Caffeine-backed version which also cached {@code Optional.empty()}.
 */
@Service
@ConditionalOnProperty(prefix = "tileboard.settings", name = "store", havingValue = "jpa", matchIfMissing = true)
public class JpaSettingsService implements SettingsService {

    private static final Logger log = LoggerFactory.getLogger(JpaSettingsService.class);

    private final SettingRepository repository;
    private final ObjectMapper objectMapper;

    public JpaSettingsService(SettingRepository repository, ObjectMapper settingsObjectMapper) {
        this.repository = repository;
        this.objectMapper = settingsObjectMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public <T> Optional<T> get(SettingKey<T> key) {
        return repository.findById(key.id())
                .map(entity -> deserialize(key, entity.getValue()));
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
