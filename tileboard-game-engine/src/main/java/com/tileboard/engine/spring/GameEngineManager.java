package com.tileboard.engine.spring;

import com.tileboard.engine.core.GameEngine;
import com.tileboard.engine.core.GameEngineImpl;
import com.tileboard.engine.core.GameRegistry;
import com.tileboard.engine.core.GameSession;
import com.tileboard.engine.event.GameEventBus;
import com.tileboard.engine.exception.EngineNotReadyException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Owns the (re)binding of the framework-free {@link GameEngine} to whatever
 * {@link com.tileboard.serial.gateway.TileGatewayClient} the application
 * currently has open.
 *
 * <p>This is the single seam between "a serial connection exists" (an
 * infrastructure concern owned by the application - e.g. its
 * {@code SerialConnectionManager} and the {@code /api/v1/ports/*} endpoints)
 * and "games can be started" (an engine concern). The engine never opens,
 * closes, or even knows the name of a serial port; it only reacts to
 * {@link GatewayConnectedEvent} / {@link GatewayDisconnectedEvent} published
 * by whoever does - so the exact same endpoints the application already
 * exposes for connecting the board are, with no further wiring, also what
 * brings the game engine up and down.
 *
 * <p>Because a {@link com.tileboard.serial.gateway.TileGatewayClient} cannot
 * be reused across reconnects, a fresh {@link GameEngineImpl} (and therefore
 * a fresh frame-listener registration on the new client) is created for every
 * {@link GatewayConnectedEvent}. Any sessions still active on a previous
 * engine are stopped before it is discarded, so a reconnect can never leave
 * orphaned sessions writing to a transport that has already been closed.
 */
public class GameEngineManager {

    private static final Logger log = LoggerFactory.getLogger(GameEngineManager.class);

    private final GameRegistry registry;
    private final GameEventBus eventBus;
    private final Duration tickInterval;

    private volatile GameEngineImpl engine;

    public GameEngineManager(GameRegistry registry, GameEventBus eventBus, Duration tickInterval) {
        this.registry = registry;
        this.eventBus = eventBus;
        this.tickInterval = tickInterval;
    }

    @EventListener
    public synchronized void onGatewayConnected(GatewayConnectedEvent event) {
        if (engine != null) {
            log.warn("Received a GatewayConnectedEvent while an engine was already bound "
                    + "(missing disconnect event?) - stopping its sessions before rebinding");
            shutdownCurrentEngine();
        }
        engine = new GameEngineImpl(
                registry, event.client(), eventBus, tickInterval,
                event.boardWidth(), event.boardHeight());
        log.info("Game engine bound to the newly connected tile gateway ({}x{})",
                event.boardWidth(), event.boardHeight());
    }

    @EventListener
    public synchronized void onGatewayDisconnected(GatewayDisconnectedEvent event) {
        shutdownCurrentEngine();
    }

    /**
     * Idempotent: safe to call even if no engine is currently bound
     * (e.g. duplicate disconnect events, or disconnect before any connect).
     */
    private void shutdownCurrentEngine() {
        GameEngineImpl current = this.engine;
        if (current == null) {
            log.debug("shutdownCurrentEngine() called with no engine bound — nothing to do");
            return;
        }
        this.engine = null; // publish null first: require()/current() never see a half-closed engine

        List<GameSession> sessions = current.activeSessions();
        try {
            current.close(); // GameEngineImpl.close() already stops every session exactly once
        } catch (RuntimeException e) {
            log.warn("Error while closing previous game engine instance", e);
        }
        log.info("Game engine unbound{}",
                sessions.isEmpty() ? "" : " (" + sessions.size() + " active session(s) stopped)");
    }

    /**
     * The currently bound engine, if the gateway is connected. Empty otherwise.
     */
    public synchronized Optional<GameEngine> current() {
        return Optional.ofNullable(engine);
    }

    /**
     * The currently bound engine.
     *
     * @throws EngineNotReadyException if no gateway is connected yet
     */
    public synchronized GameEngine require() {
        GameEngine snapshot = engine;
        if (snapshot == null) throw new EngineNotReadyException();
        return snapshot;
    }
}
