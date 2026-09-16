package com.tileboard.app.serial;

import com.tileboard.serial.gateway.TileGatewayClient;

/**
 * Published once a {@link TileGatewayClient} has been built and started
 * against the assigned serial port(s). Modules that need to talk to the
 * board (e.g. the game engine) listen for this instead of depending on
 * {@link SerialConnectionManager} directly, keeping them decoupled from how
 * and when the connection came up.
 */
public record GatewayConnectedEvent(TileGatewayClient client) {
}
