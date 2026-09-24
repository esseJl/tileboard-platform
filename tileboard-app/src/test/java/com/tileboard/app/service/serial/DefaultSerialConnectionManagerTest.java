package com.tileboard.app.service.serial;

import com.tileboard.app.config.SerialMonitorProperties;
import com.tileboard.app.config.TileboardProperties;
import com.tileboard.app.exception.PortsNotAssignedException;
import com.tileboard.app.service.device.DeviceConfigurationService;
import com.tileboard.app.settings.InMemorySettingsService;
import com.tileboard.app.settings.SettingsService;
import com.tileboard.engine.spring.GatewayDisconnectedEvent;
import com.tileboard.serial.gateway.TileGatewayClient;
import com.tileboard.serial.transport.SerialPortInfo;
import com.tileboard.serial.transport.SerialPortRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DefaultSerialConnectionManagerTest {

    /** scan-cache-ttl = 0 so every call sees the registry's current answer. */
    private static final SerialMonitorProperties NO_CACHE = new SerialMonitorProperties(null, Duration.ZERO, 2, true);

    private static DefaultSerialConnectionManager newManager(SerialPortRegistry registry) {
        return new DefaultSerialConnectionManager(
                registry,
                new TileboardProperties(115200, 8, 1, 50, 50, 0),
                NO_CACHE,
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
                mock(SerialPortRegistry.class), new TileboardProperties(115200, 8, 1, 50, 50, 0), NO_CACHE,
                mock(DeviceConfigurationService.class), settingsService, publisher);
        manager.disconnect();
        assertEquals(ConnectionState.DISCONNECTED, manager.connectionState());
        verifyNoInteractions(publisher);
    }

    // ---------------------------------------------------------------- verified link status

    private static DefaultSerialConnectionManager managerWithSession(SerialPortRegistry registry,
                                                                     ApplicationEventPublisher publisher,
                                                                     TileGatewayClient client,
                                                                     String port) {
        DefaultSerialConnectionManager manager = new DefaultSerialConnectionManager(
                registry, new TileboardProperties(115200, 8, 1, 50, 50, 0), NO_CACHE,
                mock(DeviceConfigurationService.class), new InMemorySettingsService(), publisher);
        manager.attachSessionForTest(client, port, port);
        return manager;
    }

    @Test
    void reportsHealthyOnlyWhilePortIsStillPresentOnTheHost() {
        SerialPortRegistry registry = mock(SerialPortRegistry.class);
        when(registry.listPorts()).thenReturn(List.of(new SerialPortInfo("COM3", "USB")));
        DefaultSerialConnectionManager manager =
                managerWithSession(registry, mock(ApplicationEventPublisher.class), mock(TileGatewayClient.class), "COM3");

        SerialLinkStatus status = manager.linkStatus();
        assertEquals(LinkCondition.HEALTHY, status.condition());
        assertEquals(ConnectionState.CONNECTED, manager.connectionState());
    }

    @Test
    void reportsLinkLostWhenPortDisappearsEvenThoughNobodyCalledDisconnect() {
        SerialPortRegistry registry = mock(SerialPortRegistry.class);
        when(registry.listPorts()).thenReturn(List.of(new SerialPortInfo("COM3", "USB")));
        DefaultSerialConnectionManager manager =
                managerWithSession(registry, mock(ApplicationEventPublisher.class), mock(TileGatewayClient.class), "COM3");
        assertEquals(ConnectionState.CONNECTED, manager.connectionState());

        when(registry.listPorts()).thenReturn(List.of()); // cable pulled

        SerialLinkStatus status = manager.linkStatus();
        assertEquals(LinkCondition.LINK_LOST, status.condition());
        assertEquals(ConnectionState.DISCONNECTED, status.state());
        assertTrue(status.missingPorts().contains("COM3"));
        assertEquals(ConnectionState.DISCONNECTED, manager.connectionState());
    }

    @Test
    void failedPortEnumerationIsUnverifiedNotLost() {
        SerialPortRegistry registry = mock(SerialPortRegistry.class);
        when(registry.listPorts()).thenReturn(List.of(new SerialPortInfo("COM3", "USB")));
        DefaultSerialConnectionManager manager =
                managerWithSession(registry, mock(ApplicationEventPublisher.class), mock(TileGatewayClient.class), "COM3");

        when(registry.listPorts()).thenThrow(new IllegalStateException("driver hiccup"));

        assertEquals(LinkCondition.UNVERIFIED, manager.linkStatus().condition());
        assertFalse(manager.releaseIfLinkLost(), "a failed scan must never tear a session down");
    }

    @Test
    void releaseIfLinkLostClosesClientAndPublishesDisconnectOnlyWhenPortIsGone() {
        SerialPortRegistry registry = mock(SerialPortRegistry.class);
        when(registry.listPorts()).thenReturn(List.of(new SerialPortInfo("COM3", "USB")));
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        TileGatewayClient client = mock(TileGatewayClient.class);
        DefaultSerialConnectionManager manager = managerWithSession(registry, publisher, client, "COM3");

        assertFalse(manager.releaseIfLinkLost(), "port still present -> nothing to release");
        verifyNoInteractions(publisher);

        when(registry.listPorts()).thenReturn(List.of());
        assertTrue(manager.releaseIfLinkLost());

        verify(client).close();
        verify(publisher).publishEvent(any(GatewayDisconnectedEvent.class));
        assertEquals(LinkCondition.NOT_CONNECTED, manager.linkStatus().condition());
        assertFalse(manager.releaseIfLinkLost(), "second call is a no-op");
    }
}
