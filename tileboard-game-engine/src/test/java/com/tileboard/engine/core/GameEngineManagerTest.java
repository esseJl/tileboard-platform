package com.tileboard.engine.core;


import com.tileboard.engine.event.GameEventBus;
import com.tileboard.engine.spring.GameEngineManager;
import com.tileboard.engine.spring.GatewayDisconnectedEvent;
import com.tileboard.engine.spring.TileboardEngineProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

@ExtendWith(MockitoExtension.class)
class GameEngineManagerTest {

    @Mock
    GameRegistry registry;
    @Mock
    GameEventBus eventBus;

    @Test
    void disconnectEventWithNoPriorConnectDoesNotThrow() {
        Duration interval = Duration.ofMillis(100);
        Duration sessionTtl = Duration.ofHours(1);
        TileboardEngineProperties prop = new TileboardEngineProperties();
        prop.setSessionTtl(sessionTtl);
        prop.setTickInterval(interval);
        GameEngineManager manager = new GameEngineManager(registry, eventBus, prop);

        // Original bug: this line threw a NullPointerException because shutdownCurrentEngine()
        // unconditionally called engine.activeSessions() even when engine was null.
        assertDoesNotThrow(() -> manager.onGatewayDisconnected(new GatewayDisconnectedEvent()));
    }

    @Test
    void duplicateDisconnectEventsAreIdempotent() {
        Duration interval = Duration.ofMillis(100);
        Duration sessionTtl = Duration.ofHours(1);
        TileboardEngineProperties prop = new TileboardEngineProperties();
        prop.setSessionTtl(sessionTtl);
        prop.setTickInterval(interval);

        GameEngineManager manager = new GameEngineManager(registry, eventBus, prop);
        assertDoesNotThrow(() -> {
            manager.onGatewayDisconnected(new GatewayDisconnectedEvent());
            manager.onGatewayDisconnected(new GatewayDisconnectedEvent());
        });
    }
}
