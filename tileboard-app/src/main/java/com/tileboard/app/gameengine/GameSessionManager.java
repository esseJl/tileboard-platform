package com.tileboard.app.gameengine;

import com.tileboard.app.common.exception.DeviceNotConfiguredException;
import com.tileboard.app.common.exception.GatewayNotConnectedException;
import com.tileboard.app.common.exception.NoActiveGameException;
import com.tileboard.app.device.DeviceConfiguration;
import com.tileboard.app.device.DeviceConfigurationService;
import com.tileboard.app.serial.GatewayConnectedEvent;
import com.tileboard.app.serial.GatewayDisconnectedEvent;
import com.tileboard.app.streaming.BoardStateBroadcaster;
import com.tileboard.serial.board.Board;
import com.tileboard.serial.gateway.BoardFrameListener;
import com.tileboard.serial.gateway.FrameListener;
import com.tileboard.serial.gateway.TileGatewayClient;
import com.tileboard.serial.protocol.Command;
import com.tileboard.serial.protocol.CommandType;
import com.tileboard.serial.protocol.TileTouchCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Orchestrates the lifecycle of "the game currently running on the board":
 * starts/stops games from the {@link GameRegistry}, wires the single
 * {@code DATA_IN} (tile touch) listener to whichever game is active, and
 * fans a game's output frames out to both the physical board and the SSE
 * dashboard - concerns no individual {@link Game} implementation has to
 * know about.
 *
 * <p>Reacts to {@link GatewayConnectedEvent}/{@link GatewayDisconnectedEvent}
 * rather than depending on {@code SerialConnectionManager} directly, so this
 * class has zero knowledge of ports, transports, or how the connection came
 * to be - only that a {@link TileGatewayClient} is or isn't available.
 */
@Service
public class GameSessionManager {

    private static final Logger log = LoggerFactory.getLogger(GameSessionManager.class);

    private final GameRegistry gameRegistry;
    private final DeviceConfigurationService deviceConfigurationService;
    private final BoardStateBroadcaster broadcaster;

    private volatile TileGatewayClient client;
    private volatile Game<?> activeGame;
    /**
     * The context handed to {@link #activeGame} at {@link #startInternal}, kept
     * only so its clock (see {@link GameContext#scheduleAtFixedRate}) can be
     * shut down in {@link #stopActiveGameIfAny} - no code here ever reads
     * from it.
     */
    private volatile GameContext<?> activeContext;
    /**
     * The currently-registered DATA_IN listener, kept so it can be removed
     * and replaced with one bound to fresh dimensions - see {@link #rebindTouchListener}.
     */
    private volatile FrameListener touchListener;

    public GameSessionManager(GameRegistry gameRegistry,
                               DeviceConfigurationService deviceConfigurationService,
                               BoardStateBroadcaster broadcaster) {
        this.gameRegistry = gameRegistry;
        this.deviceConfigurationService = deviceConfigurationService;
        this.broadcaster = broadcaster;
    }

    /**
     * Only records the client here - deliberately does NOT register the
     * touch listener yet. Doing it here would only work if the device was
     * already configured before the ports were connected; since that
     * ordering isn't enforced anywhere in the API, the listener is instead
     * (re)bound in {@link #startGame} against whatever the device
     * configuration actually is at that moment - see {@link #rebindTouchListener}.
     */
    @EventListener
    public synchronized void onGatewayConnected(GatewayConnectedEvent event) {
        this.client = event.client();
    }

    @EventListener
    public synchronized void onGatewayDisconnected(GatewayDisconnectedEvent event) {
        stopActiveGameIfAny();
        this.client = null;
        this.touchListener = null;
    }

    public synchronized void startGame(String gameId, GameMode mode) {
        TileGatewayClient gatewayClient = requireConnectedClient();
        DeviceConfiguration device = deviceConfigurationService.current()
                .orElseThrow(DeviceNotConfiguredException::new);

        stopActiveGameIfAny();
        rebindTouchListener(gatewayClient, device);

        GameFactory factory = gameRegistry.getFactory(gameId);
        Game<?> game = factory.create(mode, device.width(), device.height());

        gatewayClient.send(Command.START, CommandType.SET);
        startInternal(game, gatewayClient, device, mode);
        // Published only AFTER game.start() has fully run: activeGame is
        // volatile, so this write is what gives the callback thread (which
        // will call onPlayerInput for later touches) a guarantee that it
        // sees every field the game initialized during start() - publishing
        // the reference any earlier would let a touch race in against a
        // half-initialized game.
        activeGame = game;
        log.info("Started game '{}' in {} mode", gameId, mode);
    }

    /**
     * Replaces the DATA_IN listener with one built for the device's current
     * width/height. {@link BoardFrameListener} bakes its dimensions in at
     * construction time, so this must be redone whenever a game starts
     * rather than assumed to still be valid from connect time - otherwise a
     * device reconfigured after connecting (or configured only after
     * connecting) would leave touches undecoded, or decoded against stale
     * dimensions.
     */
    private void rebindTouchListener(TileGatewayClient gatewayClient, DeviceConfiguration device) {
        if (touchListener != null) {
            gatewayClient.removeFrameListener(touchListener);
        }
        BoardFrameListener<Boolean> listener = new BoardFrameListener<>(
                Command.DATA_IN, device.width(), device.height(), TileTouchCodec.instance(), this::handleTouchInput);
        gatewayClient.addFrameListener(listener);
        touchListener = listener;
        log.info("Touch listener bound for a {}x{} board", device.width(), device.height());
    }

    /** Captures the game's own tile type {@code T} so the output sink can be built type-safely. */
    private <T> void startInternal(Game<T> game, TileGatewayClient gatewayClient,
                                    DeviceConfiguration device, GameMode mode) {
        GameContext<T> context = new GameContext<>(device.width(), device.height(), mode, board -> {
            gatewayClient.sendBoard(Command.DATA_OUT, CommandType.SET, board, game.tileCodec());
            broadcaster.broadcast(board.toWireBytes(game.tileCodec()));
        });
        game.start(context);
        activeContext = context;
    }

    public synchronized void stopGame() {
        if (activeGame == null) {
            throw new NoActiveGameException();
        }
        stopActiveGameIfAny();
        if (client != null) {
            client.send(Command.STOP, CommandType.SET);
        }
    }

    public boolean hasActiveGame() {
        return activeGame != null;
    }

    private void handleTouchInput(Board<Boolean> touchedTiles) {
        log.debug("Touch input received: {} tile(s) touched at {}",
                touchedTiles.positionsWhere(Boolean.TRUE::equals).size(),
                touchedTiles.positionsWhere(Boolean.TRUE::equals));
        Game<?> game = activeGame;
        if (game == null) {
            log.debug("Touch input arrived but no game is active - dropping it");
            return;
        }
        game.onPlayerInput(touchedTiles);
    }

    private void stopActiveGameIfAny() {
        Game<?> game = activeGame;
        if (game != null) {
            game.stop();
            activeGame = null;
        }
        GameContext<?> context = activeContext;
        if (context != null) {
            context.close();
            activeContext = null;
        }
    }

    private TileGatewayClient requireConnectedClient() {
        TileGatewayClient current = client;
        if (current == null) {
            throw new GatewayNotConnectedException();
        }
        return current;
    }
}
