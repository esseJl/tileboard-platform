package com.tileboard.app.config;

import com.tileboard.app.service.serial.SerialLinkMonitor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * Schedules {@link SerialLinkMonitor}. The interval is a typed {@link java.time.Duration}
 * from {@link SerialMonitorProperties}, so no string-typed {@code @Scheduled} placeholder is involved.
 * Fixed <em>delay</em> (not fixed rate): a slow OS port enumeration can never cause overlapping runs.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "tileboard.serial-monitor", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SerialMonitorConfig implements SchedulingConfigurer {

    private final SerialLinkMonitor monitor;
    private final SerialMonitorProperties properties;

    public SerialMonitorConfig(SerialLinkMonitor monitor, SerialMonitorProperties properties) {
        this.monitor = monitor;
        this.properties = properties;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addFixedDelayTask(monitor::checkOnce, properties.interval());
    }
}
