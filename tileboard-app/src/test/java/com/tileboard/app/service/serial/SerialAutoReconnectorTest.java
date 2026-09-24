package com.tileboard.app.service.serial;

import com.tileboard.app.service.device.DeviceConfigurationService;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.mockito.Mockito.*;

class SerialAutoReconnectorTest {

    private final SerialConnectionManager manager = mock(SerialConnectionManager.class);
    private final DeviceConfigurationService device = mock(DeviceConfigurationService.class);
    private final SerialAutoReconnector reconnector = new SerialAutoReconnector(manager, device);

    private void givenAssignment(String in, String out) {
        when(manager.currentAssignment()).thenReturn(new PortAssignment(Optional.ofNullable(in), Optional.ofNullable(out)));
    }

    @Test
    void startupArmsAndConnectsWhenDeviceAndBothPortsAreConfigured() {
        when(device.isConfigured()).thenReturn(true);
        givenAssignment("COM2", "COM3");

        reconnector.onApplicationReady();

        verify(manager).armAutoReconnect();
        verify(manager).reconnectIfNeeded();
    }

    @Test
    void startupDoesNothingWhenDeviceIsNotConfigured() {
        when(device.isConfigured()).thenReturn(false);
        givenAssignment("COM2", "COM3");

        reconnector.onApplicationReady();

        verify(manager, never()).armAutoReconnect();
        verify(manager, never()).reconnectIfNeeded();
    }

    @Test
    void startupDoesNothingWhenEitherPortIsMissing() {
        when(device.isConfigured()).thenReturn(true);

        givenAssignment(null, "COM3");
        reconnector.onApplicationReady();
        givenAssignment("COM2", null);
        reconnector.onApplicationReady();

        verify(manager, never()).armAutoReconnect();
    }

    @Test
    void startupCheckNeverFailsTheApplication() {
        when(device.isConfigured()).thenThrow(new IllegalStateException("db down"));

        reconnector.onApplicationReady(); // must not throw

        verify(manager, never()).armAutoReconnect();
    }

    @Test
    void scheduledCycleDelegatesAndSwallowsFailures() {
        when(manager.reconnectIfNeeded()).thenThrow(new IllegalStateException("boom"));

        reconnector.runOnce(); // must not throw, or Spring would cancel the schedule

        verify(manager, times(1)).reconnectIfNeeded();
    }
}
