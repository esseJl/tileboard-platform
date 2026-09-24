package com.tileboard.app.health;

import com.tileboard.app.config.SerialMonitorProperties;
import com.tileboard.app.service.serial.SerialConnectionManager;
import com.tileboard.app.service.serial.SerialLinkStatus;
import org.springframework.boot.actuate.health.AbstractHealthIndicator;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Reports the REAL state of the serial link to the tile board under
 * {@code /actuator/health} (component name {@code serialLink}).
 *
 * <p>The answer comes from {@link SerialConnectionManager#linkStatus()}, which re-checks the
 * host's port list on every call (rate-limited by a short cache) - it is not a flag remembered
 * from the moment {@code connect()} succeeded, so it flips to {@code DOWN} as soon as the
 * adapter is unplugged.
 *
 * <table>
 *   <caption>Condition to status mapping</caption>
 *   <tr><td>HEALTHY</td><td>UP</td></tr>
 *   <tr><td>LINK_LOST</td><td>DOWN</td></tr>
 *   <tr><td>UNVERIFIED (host port list unreadable)</td><td>UNKNOWN</td></tr>
 *   <tr><td>NOT_CONNECTED</td><td>DOWN, or UNKNOWN when {@code tileboard.serial-monitor.not-connected-is-down=false}</td></tr>
 * </table>
 *
 * <p>Kubernetes/Docker note: liveness/readiness should use {@code /actuator/health/liveness} and
 * {@code /actuator/health/readiness} (enabled in application.yml). Those groups do not include this
 * indicator, so a missing board never gets the container restarted.
 */
@Component
public class SerialLinkHealthIndicator extends AbstractHealthIndicator {

    private final SerialConnectionManager connectionManager;
    private final SerialMonitorProperties monitorProperties;

    public SerialLinkHealthIndicator(SerialConnectionManager connectionManager,
                                     SerialMonitorProperties monitorProperties) {
        super("Serial link health check failed");
        this.connectionManager = connectionManager;
        this.monitorProperties = monitorProperties;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        SerialLinkStatus link = connectionManager.linkStatus();

        builder.status(statusFor(link))
                .withDetail("state", link.state().name())
                .withDetail("condition", link.condition().name())
                .withDetail("checkedAt", link.checkedAt().toString());

        if (link.outPort() != null) {
            builder.withDetail("outPort", link.outPort());
        }
        if (link.inPort() != null) {
            builder.withDetail("inPort", link.inPort());
        }
        if (link.connectedSince() != null) {
            builder.withDetail("connectedSince", link.connectedSince().toString());
        }
        if (!link.missingPorts().isEmpty()) {
            builder.withDetail("missingPorts", List.copyOf(link.missingPorts()));
        }
        if (link.detail() != null) {
            builder.withDetail("reason", link.detail());
        }
    }

    private Status statusFor(SerialLinkStatus link) {
        return switch (link.condition()) {
            case HEALTHY -> Status.UP;
            case LINK_LOST -> Status.DOWN;
            case UNVERIFIED -> Status.UNKNOWN;
            case NOT_CONNECTED -> monitorProperties.notConnectedIsDown() ? Status.DOWN : Status.UNKNOWN;
        };
    }
}
