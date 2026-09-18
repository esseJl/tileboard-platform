package com.tileboard.app.config;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TileboardPropertiesTest {
    @Test
    void invalidOrMissingNumericValuesFallBackToDefaults() {
        TileboardProperties p = new TileboardProperties(0,0,0,0,0,-1);
        assertEquals(115200, p.baudRate());
        assertEquals(8, p.dataBits());
        assertEquals(1, p.stopBits());
        assertEquals(50, p.readTimeoutMillis());
        assertEquals(50, p.writeTimeoutMillis());
        assertEquals(0, p.handshakeMinSequence());
    }

    @Test
    void explicitValuesArePreserved() {
        TileboardProperties p = new TileboardProperties(230400,7,2,10,20,4);
        assertEquals(230400, p.baudRate());
        assertEquals(7, p.dataBits());
        assertEquals(2, p.stopBits());
        assertEquals(10, p.readTimeoutMillis());
        assertEquals(20, p.writeTimeoutMillis());
        assertEquals(4, p.handshakeMinSequence());
    }
}
