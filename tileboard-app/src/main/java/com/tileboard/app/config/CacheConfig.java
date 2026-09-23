package com.tileboard.app.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
@EnableCaching
public class CacheConfig {

    /**
     * Shared constant so nothing else in the app has to know/repeat the literal cache name.
     */
    public static final String SETTINGS_CACHE = "settings";

    @Bean
    public CacheManager cacheManager(CacheSettingsProperties properties) {
        CaffeineCacheManager manager = new CaffeineCacheManager(SETTINGS_CACHE);
        manager.setCaffeine(Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(properties.settingsTtlSeconds()))
                .maximumSize(properties.settingsMaxSize()));
        return manager;
    }
}