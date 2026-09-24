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

    /**
     * Live-verified connection state: {@code CONNECTED} only while a session exists AND its
     * ports are still present on the host. Shorthand for {@code linkStatus().state()}.
     */
    ConnectionState connectionState();

    /**
     * Point-in-time, verified status of the serial link. Unlike a remembered flag this
     * re-checks the host's port list (rate-limited by {@code tileboard.serial-monitor.scan-cache-ttl}),
     * so an unplugged adapter is reported even though nothing called {@link #disconnect()}.
     * Never blocks on {@link #connect()}/{@link #disconnect()}, so it is safe for health probes.
     */
    SerialLinkStatus linkStatus();

    /**
     * If the current session's port has vanished from the host, releases the dead session
     * (closes the client, publishes {@link GatewayDisconnectedEvent} so the game engine unbinds).
     * Re-verifies with a fresh host scan before acting, and never acts when the scan itself fails.
     *
     * @return {@code true} if a dead session was released
     */
    boolean releaseIfLinkLost();

    /**
     * Opens the assigned port(s) and brings up the gateway client, publishing
     * {@link GatewayConnectedEvent} on success. A no-op if already connected.
     *
     * @throws PortsNotAssignedException if the output port was never assigned
     */
    void connect();

    /**
     * Closes the gateway client and any open transports, publishing {@link GatewayDisconnectedEvent}.
     * This is an <em>explicit</em> (operator) action: it also disarms auto-reconnect, so the
     * scheduler stays out of the way until {@link #connect()} is called again.
     */
    void disconnect();

    /**
     * Declares that the link should be kept up, allowing {@link #reconnectIfNeeded()} to act.
     * Called once at startup when the device and both ports are configured. Does not connect.
     */
    void armAutoReconnect();

    /** {@code true} while {@link #reconnectIfNeeded()} is allowed to re-establish the link. */
    boolean isAutoReconnectArmed();

    /**
     * Scheduler entry point. Re-establishes the link if - and only if - auto-reconnect is armed
     * (no explicit {@link #disconnect()} since) and there is no healthy session. Never throws for
     * an operational failure (missing port, port busy...): it just reports {@code false}.
     *
     * @return {@code true} if a new session was established by this call
     */
    boolean reconnectIfNeeded();
}
