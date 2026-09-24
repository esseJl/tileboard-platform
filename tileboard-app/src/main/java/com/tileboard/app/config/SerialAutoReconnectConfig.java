package com.tileboard.app.config;

import com.tileboard.app.service.serial.SerialAutoReconnector;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * Schedules {@link SerialAutoReconnector}.
 *
 * <p>Fixed <em>delay</em> (not fixed rate) so a slow port open can never cause overlapping runs, and
 * the first run is one full interval after startup: the startup connection attempt is made by
 * {@link SerialAutoReconnector#onApplicationReady()}, which runs only after the context is ready.
 * The task is always registered but is a cheap no-op while auto-reconnect is disarmed (admin
 * disconnect / never configured), so nothing has to be cancelled and re-created at runtime.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "tileboard.serial-auto-reconnect", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SerialAutoReconnectConfig implements SchedulingConfigurer {

    private final SerialAutoReconnector reconnector;
    private final SerialAutoReconnectProperties properties;

    public SerialAutoReconnectConfig(SerialAutoReconnector reconnector, SerialAutoReconnectProperties properties) {
        this.reconnector = reconnector;
        this.properties = properties;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addFixedDelayTask(new FixedDelayTask(reconnector::runOnce, properties.interval(), properties.interval()));
    }
}
