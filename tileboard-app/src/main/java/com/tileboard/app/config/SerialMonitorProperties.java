package com.tileboard.app.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Tuning for serial link verification (health indicator + background watchdog).
 *
 * <p>The watchdog itself is switched on/off with {@code tileboard.serial-monitor.enabled}
 * (default {@code true}); that flag is read directly by {@code @ConditionalOnProperty}.
 *
 * @param interval            delay between two watchdog checks
 * @param scanCacheTtl        how long one host port enumeration may be reused; keeps
 *                            {@code /actuator/health} polling from hammering the OS
 * @param lossConfirmations   consecutive watchdog checks that must see the port missing
 *                            before the session is torn down (guards against a flaky enumeration)
 * @param notConnectedIsDown  whether "no session at all" reports {@code DOWN} (HTTP 503)
 *                            instead of {@code UNKNOWN}
 */
@ConfigurationProperties(prefix = "tileboard.serial-monitor")
public record SerialMonitorProperties(
        @DefaultValue("5s") Duration interval,
        @DefaultValue("1s") Duration scanCacheTtl,
        @DefaultValue("2") int lossConfirmations,
        @DefaultValue("true") boolean notConnectedIsDown) {

    public SerialMonitorProperties {
        if (interval == null || interval.isZero() || interval.isNegative()) {
            interval = Duration.ofSeconds(5);
        }
        if (scanCacheTtl == null || scanCacheTtl.isNegative()) {
            scanCacheTtl = Duration.ofSeconds(1);
        }
        if (lossConfirmations < 1) {
            lossConfirmations = 1;
        }
    }

    /** Defaults, for code (and tests) that build the manager by hand. */
    public static SerialMonitorProperties defaults() {
        return new SerialMonitorProperties(null, null, 2, true);
    }
}
