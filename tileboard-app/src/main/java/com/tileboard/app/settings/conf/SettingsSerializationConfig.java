package com.tileboard.app.settings.conf;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * A dedicated ObjectMapper for settings (de)serialization, independent of whatever the
 * web layer's ObjectMapper does (e.g. custom (de)serializers added later for API DTOs).
 * Settings are a persistence concern, not a wire/API concern, so they get their own,
 * deliberately conservative mapper - registered explicitly rather than relying on
 * whatever modules happen to be on the classpath.
 */
@Configuration
public class SettingsSerializationConfig {

    @Bean
    public ObjectMapper settingsObjectMapper() {
        return new ObjectMapper()
                .registerModule(new Jdk8Module())   // Optional<T> fields (e.g. PortAssignment)
                .registerModule(new JavaTimeModule())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }
}