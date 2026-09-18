package com.tileboard.engine.spring;

/**
 * Published when the serial connection to the board is torn down.
 *
 * @see GatewayConnectedEvent
 */
public record GatewayDisconnectedEvent() {
}
