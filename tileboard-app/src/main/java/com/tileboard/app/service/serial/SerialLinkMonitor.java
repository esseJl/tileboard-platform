package com.tileboard.app.service.serial;

import com.tileboard.app.config.SerialMonitorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Watchdog that keeps the in-memory session honest.
 *
 * <p>{@code /actuator/health} only <em>reports</em> a lost link; this component also
 * <em>acts</em> on it: once the session's port has been missing for
 * {@code tileboard.serial-monitor.loss-confirmations} consecutive checks it calls
 * {@link SerialConnectionManager#releaseIfLinkLost()}, which closes the dead client and
 * publishes {@code GatewayDisconnectedEvent}. The game engine therefore unbinds instead of
 * writing into a port that no longer exists, and the operator can simply call
 * {@code POST /api/v1/ports/connect} again after re-plugging.
 *
 * <p>Scheduled by {@link com.tileboard.app.config.SerialMonitorConfig}.
 */
@Component
@ConditionalOnProperty(prefix = "tileboard.serial-monitor", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SerialLinkMonitor {

    private static final Logger log = LoggerFactory.getLogger(SerialLinkMonitor.class);

    private final SerialConnectionManager connectionManager;
    private final int lossConfirmations;
    private final AtomicInteger consecutiveLost = new AtomicInteger();

    public SerialLinkMonitor(SerialConnectionManager connectionManager, SerialMonitorProperties properties) {
        this.connectionManager = connectionManager;
        this.lossConfirmations = properties.lossConfirmations();
    }

    /** One watchdog cycle. Never throws - a failing check must not cancel the schedule. */
    public void checkOnce() {
        try {
            SerialLinkStatus link = connectionManager.linkStatus();
            if (link.condition() != LinkCondition.LINK_LOST) {
                consecutiveLost.set(0);
                return;
            }

            int seen = consecutiveLost.incrementAndGet();
            if (seen < lossConfirmations) {
                log.warn("Serial port(s) {} missing ({}/{} checks) - waiting for confirmation",
                        link.missingPorts(), seen, lossConfirmations);
                return;
            }

            consecutiveLost.set(0);
            if (connectionManager.releaseIfLinkLost()) {
                log.error("Serial link to the tile board was lost (port(s) {}); gateway released. "
                        + "Re-plug the adapter and call POST /api/v1/ports/connect.", link.missingPorts());
            }
        } catch (RuntimeException e) {
            log.error("Serial link check failed", e);
        }
    }
}
