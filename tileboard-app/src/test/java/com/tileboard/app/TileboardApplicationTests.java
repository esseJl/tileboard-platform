package com.tileboard.app;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class TileboardApplicationTests {

    @Test
    void contextLoads() {
        // Verifies the whole wiring graph (config, serial, gameengine,
        // streaming, device, the example game) is valid on startup.
    }
}
