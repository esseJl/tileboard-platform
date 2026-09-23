package com.tileboard.app.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tunables for the in-process cache sitting in front of settings reads. Settings are
 * read far more often than written (every serial connect, every game start) so caching
 * materially cuts DB load; kept externally configurable rather than fixed constants
 * since the right TTL depends on deployment topology (a single instance can cache
 * almost indefinitely, a cluster needs a short TTL to see another instance's writes).
 */
@ConfigurationProperties(prefix = "tileboard.cache")
public record CacheSettingsProperties(long settingsTtlSeconds, long settingsMaxSize) {

    public CacheSettingsProperties {
        if (settingsTtlSeconds <= 0) settingsTtlSeconds = 300;
        if (settingsMaxSize <= 0) settingsMaxSize = 100;
    }
}