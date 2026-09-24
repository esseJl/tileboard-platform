package com.tileboard.app.health;

import com.tileboard.app.config.SerialMonitorProperties;
import com.tileboard.app.service.serial.ConnectionState;
import com.tileboard.app.service.serial.LinkCondition;
import com.tileboard.app.service.serial.SerialConnectionManager;
import com.tileboard.app.service.serial.SerialLinkStatus;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SerialLinkHealthIndicatorTest {

    private static Health healthFor(SerialLinkStatus link, boolean notConnectedIsDown) {
        SerialConnectionManager manager = mock(SerialConnectionManager.class);
        when(manager.linkStatus()).thenReturn(link);
        return new SerialLinkHealthIndicator(manager,
                new SerialMonitorProperties(null, null, 2, notConnectedIsDown)).health();
    }

    private static SerialLinkStatus status(ConnectionState state, LinkCondition condition, Set<String> missing) {
        return new SerialLinkStatus(state, condition, "COM3", "COM3", missing, Instant.now(), Instant.now(), "x");
    }

    @Test
    void healthyLinkIsUp() {
        Health h = healthFor(status(ConnectionState.CONNECTED, LinkCondition.HEALTHY, Set.of()), true);
        assertEquals(Status.UP, h.getStatus());
        assertEquals("COM3", h.getDetails().get("outPort"));
    }

    @Test
    void lostLinkIsDownAndNamesTheMissingPort() {
        Health h = healthFor(status(ConnectionState.DISCONNECTED, LinkCondition.LINK_LOST, Set.of("COM3")), true);
        assertEquals(Status.DOWN, h.getStatus());
        assertEquals("LINK_LOST", h.getDetails().get("condition"));
        assertEquals(java.util.List.of("COM3"), h.getDetails().get("missingPorts"));
    }

    @Test
    void unverifiedLinkIsUnknown() {
        Health h = healthFor(status(ConnectionState.CONNECTED, LinkCondition.UNVERIFIED, Set.of()), true);
        assertEquals(Status.UNKNOWN, h.getStatus());
    }

    @Test
    void notConnectedFollowsTheConfiguredSeverity() {
        SerialLinkStatus none = SerialLinkStatus.notConnected(Instant.now());
        assertEquals(Status.DOWN, healthFor(none, true).getStatus());
        assertEquals(Status.UNKNOWN, healthFor(none, false).getStatus());
    }

    @Test
    void anExplodingManagerBecomesDownInsteadOfAnException() {
        SerialConnectionManager manager = mock(SerialConnectionManager.class);
        when(manager.linkStatus()).thenThrow(new IllegalStateException("boom"));
        Health h = new SerialLinkHealthIndicator(manager, SerialMonitorProperties.defaults()).health();
        assertEquals(Status.DOWN, h.getStatus());
    }
}
