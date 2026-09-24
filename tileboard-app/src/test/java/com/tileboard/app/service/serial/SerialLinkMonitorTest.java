package com.tileboard.app.service.serial;

import com.tileboard.app.config.SerialMonitorProperties;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.mockito.Mockito.*;

class SerialLinkMonitorTest {

    private static SerialLinkStatus lost() {
        return new SerialLinkStatus(ConnectionState.DISCONNECTED, LinkCondition.LINK_LOST,
                "COM3", "COM3", Set.of("COM3"), Instant.now(), Instant.now(), "gone");
    }

    private static SerialLinkStatus healthy() {
        return new SerialLinkStatus(ConnectionState.CONNECTED, LinkCondition.HEALTHY,
                "COM3", "COM3", Set.of(), Instant.now(), Instant.now(), "ok");
    }

    @Test
    void releasesOnlyAfterTheConfiguredNumberOfConsecutiveLostChecks() {
        SerialConnectionManager manager = mock(SerialConnectionManager.class);
        when(manager.linkStatus()).thenReturn(lost());
        when(manager.releaseIfLinkLost()).thenReturn(true);
        SerialLinkMonitor monitor = new SerialLinkMonitor(manager, new SerialMonitorProperties(null, null, 2, true));

        monitor.checkOnce();
        verify(manager, never()).releaseIfLinkLost();

        monitor.checkOnce();
        verify(manager, times(1)).releaseIfLinkLost();
    }

    @Test
    void aHealthyCheckResetsTheLossCounter() {
        SerialConnectionManager manager = mock(SerialConnectionManager.class);
        when(manager.linkStatus()).thenReturn(lost(), healthy(), lost());
        SerialLinkMonitor monitor = new SerialLinkMonitor(manager, new SerialMonitorProperties(null, null, 2, true));

        monitor.checkOnce(); // lost 1/2
        monitor.checkOnce(); // healthy -> reset
        monitor.checkOnce(); // lost 1/2 again

        verify(manager, never()).releaseIfLinkLost();
    }

    @Test
    void neverPropagatesExceptionsSoTheScheduleSurvives() {
        SerialConnectionManager manager = mock(SerialConnectionManager.class);
        when(manager.linkStatus()).thenThrow(new IllegalStateException("boom"));
        SerialLinkMonitor monitor = new SerialLinkMonitor(manager, SerialMonitorProperties.defaults());

        monitor.checkOnce(); // must not throw
        verify(manager, never()).releaseIfLinkLost();
    }
}
