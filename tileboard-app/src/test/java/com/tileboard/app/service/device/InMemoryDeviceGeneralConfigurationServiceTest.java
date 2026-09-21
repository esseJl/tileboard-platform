package com.tileboard.app.service.device;

import com.tileboard.app.config.DeviceConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryDeviceGeneralConfigurationServiceTest {
    @Test
    void startsEmptyAndReplacesConfigurationAtomically() {
        InMemoryDeviceConfigurationService service = new InMemoryDeviceConfigurationService();
        assertTrue(service.current().isEmpty());
        DeviceConfiguration first = service.configure(8, 8);
        assertEquals(64, first.tileCount());
        assertEquals(first, service.current().orElseThrow());
        DeviceConfiguration second = service.configure(10, 10);
        assertEquals(second, service.current().orElseThrow());
    }

    @Test
    void configurationEnforcesProtocolGeometryLimits() {
        assertThrows(IllegalArgumentException.class, () -> new DeviceConfiguration(0, 8));
        assertThrows(IllegalArgumentException.class, () -> new DeviceConfiguration(16, 16));
        assertEquals(255, new DeviceConfiguration(15, 17).tileCount());
    }
}
