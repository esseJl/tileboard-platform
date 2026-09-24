package com.tileboard.app.service.serial;

import com.tileboard.app.service.device.DeviceConfigurationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Keeps the tile-board link up without operator involvement.
 *
 * <ul>
 *   <li><b>Startup</b> ({@link ApplicationReadyEvent}): if the device geometry is stored AND both
 *       the IN and OUT ports are assigned, auto-reconnect is armed and a first connection is
 *       attempted immediately.</li>
 *   <li><b>Every {@code tileboard.serial-auto-reconnect.interval}</b> (default 1 minute, see
 *       {@link com.tileboard.app.config.SerialAutoReconnectConfig}): {@link #runOnce()} asks the
 *       manager to re-establish the link if it was lost.</li>
 *   <li><b>Admin disconnect</b>: {@link SerialConnectionManager#disconnect()} disarms the manager;
 *       from then on every tick is a no-op until an admin calls {@code connect()} again.</li>
 * </ul>
 *
 * <p>The intent flag lives inside the manager (guarded by the same monitor as connect/disconnect),
 * not here, so "admin disconnected" and "scheduler reconnects" can never race. Distinct from
 * {@link SerialLinkMonitor}, which only <em>releases</em> a dead session (every few seconds);
 * this component brings it back (every minute).
 */
@Component
@ConditionalOnProperty(prefix = "tileboard.serial-auto-reconnect", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SerialAutoReconnector {

    private static final Logger log = LoggerFactory.getLogger(SerialAutoReconnector.class);

    private final SerialConnectionManager connectionManager;
    private final DeviceConfigurationService deviceConfigurationService;

    public SerialAutoReconnector(SerialConnectionManager connectionManager,
                                 DeviceConfigurationService deviceConfigurationService) {
        this.connectionManager = connectionManager;
        this.deviceConfigurationService = deviceConfigurationService;
    }

    /** Runs once the application is fully started. Must never fail the startup. */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        try {
            boolean deviceConfigured = deviceConfigurationService.isConfigured();
            PortAssignment assignment = connectionManager.currentAssignment();
            boolean inAssigned = assignment.inPort().isPresent();
            boolean outAssigned = assignment.outPort().isPresent();

            if (!(deviceConfigured && inAssigned && outAssigned)) {
                log.info("Serial auto-reconnect NOT armed at startup (deviceConfigured={}, inPortAssigned={}, "
                        + "outPortAssigned={}). It arms itself on the first explicit POST /api/v1/ports/connect.",
                        deviceConfigured, inAssigned, outAssigned);
                return;
            }

            connectionManager.armAutoReconnect();
            log.info("Serial auto-reconnect armed (in={}, out={}); attempting initial connection",
                    assignment.inPort().orElseThrow(), assignment.outPort().orElseThrow());
            runOnce();
        } catch (RuntimeException e) {
            log.error("Serial auto-reconnect startup check failed; it will not be armed until an admin connects", e);
        }
    }

    /** One scheduler cycle. Never throws - a failing attempt must not cancel the schedule. */
    public void runOnce() {
        try {
            if (connectionManager.reconnectIfNeeded()) {
                log.info("Serial link re-established by auto-reconnect");
            }
        } catch (RuntimeException e) {
            log.error("Serial auto-reconnect cycle failed", e);
        }
    }
}
