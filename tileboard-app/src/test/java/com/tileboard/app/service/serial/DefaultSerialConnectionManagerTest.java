package com.tileboard.app.service.serial;

import com.tileboard.app.config.TileboardProperties;
import com.tileboard.app.exception.PortsNotAssignedException;
import com.tileboard.app.service.device.DeviceConfigurationService;
import com.tileboard.app.settings.InMemorySettingsService;
import com.tileboard.app.settings.SettingsService;
import com.tileboard.serial.transport.SerialPortInfo;
import com.tileboard.serial.transport.SerialPortRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DefaultSerialConnectionManagerTest {

    private static DefaultSerialConnectionManager newManager(SerialPortRegistry registry) {
        return new DefaultSerialConnectionManager(
                registry,
                new TileboardProperties(115200, 8, 1, 50, 50, 0),
                mock(DeviceConfigurationService.class),
                mock(SettingsService.class),
                mock(ApplicationEventPublisher.class));
    }

    @Test
    void listsDistinctPortsPreservingDescriptionAndStartsDisconnected() {
        SerialPortRegistry registry = mock(SerialPortRegistry.class);
        when(registry.listPorts()).thenReturn(List.of(
                new SerialPortInfo("COM1", "USB A"),
                new SerialPortInfo("COM1", "duplicate"),
                new SerialPortInfo("COM2", "USB B")));
        DefaultSerialConnectionManager manager = newManager(registry);
        assertEquals(ConnectionState.DISCONNECTED, manager.connectionState());
        assertEquals(2, manager.listAvailablePorts().size());
        assertEquals("USB A", manager.listAvailablePorts().get(0).description());
    }

/*    @Test
    void assignmentIsReportedAndConnectRequiresOutputPort() {
        SerialPortRegistry registry = mock(SerialPortRegistry.class);
        when(registry.listPorts()).thenReturn(List.of());
        DefaultSerialConnectionManager manager = newManager(registry);
        assertEquals(null, manager.currentAssignment());
        manager.assign(PortRole.IN, "COM2");
        assertEquals("COM2", manager.currentAssignment().inPort().orElseThrow());
        assertTrue(manager.currentAssignment().outPort().isEmpty());
        assertThrows(PortsNotAssignedException.class, manager::connect);
        verify(registry, never()).open(anyString(), any());
    }*/

    @Test
    void disconnectIsIdempotentWhenAlreadyDisconnected() {
        SettingsService settingsService = new InMemorySettingsService();
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        DefaultSerialConnectionManager manager = new DefaultSerialConnectionManager(
                mock(SerialPortRegistry.class), new TileboardProperties(115200, 8, 1, 50, 50, 0),
                mock(DeviceConfigurationService.class), settingsService, publisher);
        manager.disconnect();
        assertEquals(ConnectionState.DISCONNECTED, manager.connectionState());
        verifyNoInteractions(publisher);
    }
}
