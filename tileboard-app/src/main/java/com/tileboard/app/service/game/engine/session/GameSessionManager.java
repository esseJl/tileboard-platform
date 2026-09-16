package com.tileboard.app.service.game.engine.session;

import com.tileboard.app.exception.GatewayNotConnectedException;
import com.tileboard.app.exception.DeviceNotConfiguredException;
import com.tileboard.app.exception.NoActiveGameException;
import com.tileboard.app.service.device.DeviceConfigurationService;
import com.tileboard.app.service.game.engine.context.GameContext;
import com.tileboard.app.service.game.engine.core.Game;
import com.tileboard.app.service.game.engine.core.GameFactory;
import com.tileboard.app.service.game.engine.core.GameMode;
import com.tileboard.app.service.game.engine.registry.GameRegistry;
import com.tileboard.app.service.serial.ConnectionState;
import com.tileboard.app.service.serial.GatewayConnectedEvent;
import com.tileboard.app.service.serial.GatewayDisconnectedEvent;
import com.tileboard.app.service.serial.SerialConnectionManager;
import com.tileboard.app.service.streaming.BoardStateBroadcaster;
import com.tileboard.serial.board.Board;
import com.tileboard.serial.board.Position;
import com.tileboard.serial.board.TileCodec;
import com.tileboard.serial.gateway.FrameListener;
import com.tileboard.serial.gateway.TileGatewayClient;
import com.tileboard.serial.protocol.Command;
import com.tileboard.serial.protocol.Frame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Owns the active game session. Reacts to gateway connect/disconnect,
 * routes incoming DATA_IN frames to the game, and exposes start/stop
 * operations for the REST layer.
 */
@Service
public class GameSessionManager {

    private static final Logger log = LoggerFactory.getLogger(GameSessionManager.class);

    private final GameRegistry registry;
    private final DeviceConfigurationService deviceConfigurationService;
    private final SerialConnectionManager serialConnectionManager;
    private final BoardStateBroadcaster broadcaster;

    private final AtomicReference<GameSession> active = new AtomicReference<>();
    private final AtomicReference<TileGatewayClient> gatewayRef = new AtomicReference<>();

    private final FrameListener inputListener = this::onFrame;

    public GameSessionManager(
            GameRegistry registry,
            DeviceConfigurationService deviceConfigurationService,
            SerialConnectionManager serialConnectionManager,
            BoardStateBroadcaster broadcaster
    ) {
        this.registry = registry;
        this.deviceConfigurationService = deviceConfigurationService;
        this.serialConnectionManager = serialConnectionManager;
        this.broadcaster = broadcaster;
    }

    @EventListener
    public void onGatewayConnected(GatewayConnectedEvent event) {
        TileGatewayClient client = event.client();
        gatewayRef.set(client);
        client.addFrameListener(inputListener);
        log.info("GameSessionManager attached to gateway");
    }

    @EventListener
    public void onGatewayDisconnected(GatewayDisconnectedEvent event) {
        stopInternal("gateway disconnected");
        TileGatewayClient client = gatewayRef.getAndSet(null);
        if (client != null) {
            client.removeFrameListener(inputListener);
        }
        log.info("GameSessionManager detached from gateway");
    }

    public synchronized GameSession start(String gameId, GameMode mode) {
        TileGatewayClient gateway = gatewayRef.get();
        if (gateway == null || serialConnectionManager.connectionState() != ConnectionState.CONNECTED) {
            throw new GatewayNotConnectedException();
        }

        stopInternal("starting new game");

        var device = deviceConfigurationService.current()
                .orElseThrow(() -> new DeviceNotConfiguredException());
        int width = device.width();
        int height = device.height();

        GameFactory factory = registry.require(gameId);
        Game<?> game = factory.create(mode, width, height);

        @SuppressWarnings({"unchecked", "rawtypes"})
        GameContext<?> context = new GameContext(
                width, height, mode,
                game.tileCodec(),
                (java.util.function.Supplier) () -> null,
                gateway,
                (java.util.function.Consumer<byte[]>) broadcaster::broadcast
        );

        GameSession session = new GameSession(
                gameId,
                factory.definition(),
                mode,
                game,
                context,
                Instant.now()
        );
        active.set(session);

        try {
            @SuppressWarnings("unchecked")
            Game<Object> g = (Game<Object>) game;
            @SuppressWarnings("unchecked")
            GameContext<Object> ctx = (GameContext<Object>) context;
            g.start(ctx);
        } catch (Exception e) {
            active.set(null);
            throw e;
        }

        log.info("Started game '{}' in mode {}", gameId, mode);
        return session;
    }

    public synchronized void stop() {
        if (active.get() == null) {
            throw new NoActiveGameException();
        }
        stopInternal("explicit stop");
    }

    private void stopInternal(String reason) {
        GameSession session = active.getAndSet(null);
        if (session == null) {
            return;
        }
        try {
            session.context().deactivate();
            session.game().stop();
        } catch (Exception e) {
            log.warn("Error while stopping game: {}", e.getMessage());
        }
        log.info("Stopped game '{}' ({})", session.gameId(), reason);
    }

    public Optional<GameSession> current() {
        return Optional.ofNullable(active.get());
    }

    private void onFrame(Frame frame) {
        if (frame.command() != Command.DATA_IN) {
            return;
        }
        GameSession session = active.get();
        if (session == null) {
            return;
        }

        GameContext<?> ctx = session.context();
        if (!ctx.isActive()) {
            return;
        }

        try {
            Board<Boolean> touched = Board.fromWireBytes(
                    frame.payload(),
                    ctx.width(),
                    ctx.height(),
                    TileCodec.booleanState()
            );

            // Record rising edges into the built-in touch history
            for (Position p : touched.positionsWhere(Boolean.TRUE::equals)) {
                // Simple rising-edge approximation: every reported press is recorded.
                // Games that need finer debouncing can keep their own previous state.
                ctx.recordTouch(p);
            }

            ctx.timer().tick();

            @SuppressWarnings("unchecked")
            Game<Object> game = (Game<Object>) session.game();
            game.onPlayerInput(touched);
        } catch (Exception e) {
            log.warn("Error while processing player input: {}", e.getMessage(), e);
        }
    }
}
