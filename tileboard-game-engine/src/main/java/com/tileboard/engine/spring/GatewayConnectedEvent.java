package com.tileboard.engine.spring;

import com.tileboard.serial.gateway.TileGatewayClient;

import java.util.Objects;

/**
 * Published by whichever application component owns the physical serial
 * connection (typically the same service backing the port-assignment/connect
 * REST endpoints) once a {@link TileGatewayClient} has been built and
 * started.
 *
 * <p>Lives in the game-engine's optional Spring layer - rather than in the
 * application - so that both the engine and the application can depend on
 * this single definition: the application already depends on
 * {@code tileboard-game-engine}, so it can publish this event, while the
 * engine (a lower-level library) must never depend on application code.
 * Defining two separate, identically-shaped event types (one per module)
 * would silently break the wiring below, since {@link GameEngineManager}
 * would then be listening for an event nobody ever publishes.
 *
 * @param client the already-open, already-started gateway to bind the game
 *               engine to. Plain Spring application events (no
 *               {@code ApplicationEvent} subclassing required) are used here,
 *               matching {@code ApplicationEventPublisher#publishEvent(Object)}.
 */
public record GatewayConnectedEvent(TileGatewayClient client) {

    public GatewayConnectedEvent {
        Objects.requireNonNull(client, "client");
    }
}
