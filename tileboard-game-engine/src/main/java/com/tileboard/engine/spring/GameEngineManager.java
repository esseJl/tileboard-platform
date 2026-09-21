package com.tileboard.engine.spring;

import com.tileboard.engine.core.GameEngine;
import com.tileboard.engine.core.GameEngineImpl;
import com.tileboard.engine.core.GameRegistry;
import com.tileboard.engine.core.GameSession;
import com.tileboard.engine.event.GameEventBus;
import com.tileboard.engine.exception.EngineNotReadyException;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Owns the (re)binding of the framework-free {@link GameEngine} to whatever
 * {@link com.tileboard.serial.gateway.TileGatewayClient} the application
 * currently has open. See class-level docs of the previous version for the
 * architectural rationale — behaviour here is unchanged except for the fixes
 * below.
 *
 * <p><b>Fixes applied:</b>
 * <ul>
 *   <li>{@link #shutdownCurrentEngine()} is now null-safe and idempotent —
 *       calling it with no engine bound (duplicate/out-of-order disconnect
 *       events) is a safe no-op instead of an NPE.</li>
 *   <li>{@code engine} is nulled out <em>before</em> the (possibly slow)
 *       {@code close()} call, so {@link #current()}/{@link #require()} never
 *       observe a half-closed engine from another thread.</li>
 *   <li>Session cleanup is no longer duplicated between {@code close()} and
 *       this class — {@code GameEngineImpl.close()} is the single source of
 *       truth for stopping sessions.</li>
 *   <li>Session TTL is now sourced from {@link TileboardEngineProperties}
 *       instead of being hard-coded inside {@code GameEngineImpl}.</li>
 *   <li>{@link #shutdownOnContextClose()} guarantees the engine (and its
 *       daemon threads) are released when the Spring context stops, even if
 *       no explicit disconnect event was ever published.</li>
 * </ul>
 */
public class GameEngineManager {

    private static final Logger log = LoggerFactory.getLogger(GameEngineManager.class);

    private final GameRegistry registry;
    private final GameEventBus eventBus;
    private final Duration tickInterval;
    private final Duration sessionTtl;

    private volatile GameEngineImpl engine;

    public GameEngineManager(GameRegistry registry, GameEventBus eventBus, TileboardEngineProperties props) {
        this.registry = registry;
        this.eventBus = eventBus;
        this.tickInterval = props.getTickInterval();
        this.sessionTtl = props.getSessionTtl();
    }

    @EventListener
    public synchronized void onGatewayConnected(GatewayConnectedEvent event) {
        if (engine != null) {
            log.warn("Received a GatewayConnectedEvent while an engine was already bound "
                    + "(missing disconnect event?) - stopping its sessions before rebinding");
            shutdownCurrentEngine();
        }
        engine = new GameEngineImpl(registry, event.client(), eventBus, tickInterval, sessionTtl,
                event.boardWidth(), event.boardHeight());
        log.info("Game engine bound to the newly connected tile gateway ({}x{})",
                event.boardWidth(), event.boardHeight());
    }

    @EventListener
    public synchronized void onGatewayDisconnected(GatewayDisconnectedEvent event) {
        shutdownCurrentEngine();
    }

    /**
     * Idempotent and null-safe: safe to call with no engine bound.
     */
    private void shutdownCurrentEngine() {
        GameEngineImpl current = this.engine;
        if (current == null) {
            log.debug("shutdownCurrentEngine() called with no engine bound — nothing to do");
            return;
        }
        this.engine = null; // visible to current()/require() immediately, before the potentially slow close()

        List<GameSession> sessions = current.activeSessions();
        try {
            current.close(); // sole owner of "stop every session" logic — no duplicate iteration here
        } catch (RuntimeException e) {
            log.warn("Error while closing previous game engine instance", e);
        }
        log.info("Game engine unbound{}",
                sessions.isEmpty() ? "" : " (" + sessions.size() + " active session(s) stopped)");
    }

    public synchronized Optional<GameEngine> current() {
        return Optional.ofNullable(engine);
    }

    public synchronized GameEngine require() {
        GameEngine snapshot = engine;
        if (snapshot == null) throw new EngineNotReadyException();
        return snapshot;
    }

    /**
     * Ensures daemon threads are released even if the app shuts down without a disconnect event.
     */
    @PreDestroy
    public synchronized void shutdownOnContextClose() {
        shutdownCurrentEngine();
    }
}