package com.tileboard.app.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Tuning for the serial auto-reconnect scheduler.
 *
 * <p>The scheduler is switched on/off with {@code tileboard.serial-auto-reconnect.enabled}
 * (default {@code true}); that flag is read directly by {@code @ConditionalOnProperty}.
 *
 * @param interval delay between the END of one reconnect attempt and the start of the next
 */
@ConfigurationProperties(prefix = "tileboard.serial-auto-reconnect")
public record SerialAutoReconnectProperties(@DefaultValue("1m") Duration interval) {

    public SerialAutoReconnectProperties {
        if (interval == null || interval.isZero() || interval.isNegative()) {
            interval = Duration.ofMinutes(1);
        }
    }
}
