package com.tileboard.engine.exception;

/**
 * Thrown when a game is requested to start (or any other gateway-dependent
 * engine operation is attempted) before the application has an active serial
 * connection to the tile board.
 *
 * <p>Mirrors, at the engine layer, the same "not connected yet" condition the
 * application already surfaces at the port level (see
 * {@code GatewayNotConnectedException} / {@code PortsNotAssignedException} in
 * {@code tileboard-app}). This is the exception
 * {@link com.tileboard.engine.spring.GameEngineManager#require()} throws
 * when no {@link com.tileboard.engine.spring.GatewayConnectedEvent} has been
 * observed yet (or the gateway has since disconnected).
 */
public final class EngineNotReadyException extends GameEngineException {

    public EngineNotReadyException() {
        super("The game engine is not ready: no serial gateway is currently connected. "
                + "Assign and connect the serial ports first.");
    }
}
