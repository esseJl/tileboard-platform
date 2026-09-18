package com.tileboard.app.service.serial;

import com.tileboard.app.exception.PortsNotAssignedException;
import com.tileboard.engine.spring.GatewayConnectedEvent;
import com.tileboard.engine.spring.GatewayDisconnectedEvent;

import java.util.List;

/**
 * Coordinates the application's single logical connection to the tile
 * board: which system ports are assigned to which {@link PortRole}, and the
 * open/closed lifecycle of the resulting {@link com.tileboard.serial.gateway.TileGatewayClient}.
 *
 * <p>This is the seam between "the board is reachable" (a connectivity
 * concern) and "a game is running" (an engine concern, see
 * {@code com.tileboard.engine.spring.GameEngineManager} in
 * {@code tileboard-game-engine}) - the game engine never opens or closes a
 * port itself, it only reacts to {@link GatewayConnectedEvent}/{@link GatewayDisconnectedEvent},
 * which this implementation publishes. Those two events live in the engine
 * module (not here) precisely so both this application and the engine can
 * depend on the same definition without a circular module dependency.
 */
public interface SerialConnectionManager {

    /** Every serial port currently visible to the host OS. */
    List<SerialPortSummary> listAvailablePorts();

    /** Assigns {@code portName} to {@code role}. Takes effect on the next {@link #connect()}. */
    void assign(PortRole role, String portName);

    PortAssignment currentAssignment();

    ConnectionState connectionState();

    /**
     * Opens the assigned port(s) and brings up the gateway client, publishing
     * {@link GatewayConnectedEvent} on success. A no-op if already connected.
     *
     * @throws PortsNotAssignedException if the output port was never assigned
     */
    void connect();

    /** Closes the gateway client and any open transports, publishing {@link GatewayDisconnectedEvent}. */
    void disconnect();
}
