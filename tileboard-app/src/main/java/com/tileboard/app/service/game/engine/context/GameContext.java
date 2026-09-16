package com.tileboard.app.service.game.engine.context;

import com.tileboard.app.service.game.engine.color.TileColor;
import com.tileboard.app.service.game.engine.core.GameMode;
import com.tileboard.app.service.game.engine.event.GameEventListener;
import com.tileboard.app.service.game.engine.pattern.PathGenerator;
import com.tileboard.app.service.game.engine.score.ScoreState;
import com.tileboard.app.service.game.engine.support.NeighborUtils;
import com.tileboard.app.service.game.engine.timer.GameTimer;
import com.tileboard.app.service.game.engine.touch.TouchHistory;
import com.tileboard.serial.board.Board;
import com.tileboard.serial.board.Position;
import com.tileboard.serial.board.TileCodec;
import com.tileboard.serial.gateway.TileGatewayClient;
import com.tileboard.serial.protocol.Command;
import com.tileboard.serial.protocol.CommandType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * All built-in platform services exposed to a running game.
 *
 * <p>A game never talks to the serial layer or the broadcaster directly;
 * it only uses this context. That keeps game code pure and testable.
 *
 * @param <T> tile type of the active game
 */
public final class GameContext<T> {

    private static final Logger log = LoggerFactory.getLogger(GameContext.class);

    private final int width;
    private final int height;
    private final GameMode mode;
    private final TileCodec<T> codec;
    private final TileGatewayClient gateway;
    private final Consumer<byte[]> broadcaster;

    private final Board<T> board;
    private final TouchHistory touchHistory = new TouchHistory();
    private final ScoreState score = new ScoreState();
    private final GameTimer timer = new GameTimer();
    private final PathGenerator pathGenerator;
    private final List<GameEventListener> listeners = new CopyOnWriteArrayList<>();

    private volatile boolean active = true;

    public GameContext(
            int width,
            int height,
            GameMode mode,
            TileCodec<T> codec,
            T initialTile,
            TileGatewayClient gateway,
            Consumer<byte[]> broadcaster
    ) {
        this(width, height, mode, codec, () -> initialTile, gateway, broadcaster);
    }

    public GameContext(
            int width,
            int height,
            GameMode mode,
            TileCodec<T> codec,
            java.util.function.Supplier<T> initialTileSupplier,
            TileGatewayClient gateway,
            Consumer<byte[]> broadcaster
    ) {
        this.width = width;
        this.height = height;
        this.mode = Objects.requireNonNull(mode);
        this.codec = Objects.requireNonNull(codec);
        this.gateway = gateway;
        this.broadcaster = broadcaster;
        this.board = new Board<>(width, height, initialTileSupplier);
        this.pathGenerator = new PathGenerator(width, height);
        this.timer.start();
    }

    // --- geometry ---

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public GameMode mode() {
        return mode;
    }

    // --- board ---

    public Board<T> board() {
        return board;
    }

    public void setTile(Position p, T value) {
        board.set(p, value);
    }

    public void setTile(int row, int col, T value) {
        board.set(row, col, value);
    }

    public void fill(T value) {
        board.fill(value);
    }

    public void clear(T offValue) {
        board.fill(offValue);
    }

    /**
     * Pushes the current board state to the physical hardware and to any
     * SSE subscribers. Call this whenever the visual state changes.
     */
    public void publish() {
        if (!active || gateway == null) {
            return;
        }
        try {
            byte[] wire = board.toWireBytes(codec);
            gateway.sendBoard(Command.DATA_OUT, CommandType.SET, board, codec);
            if (broadcaster != null) {
                broadcaster.accept(wire);
            }
        } catch (Exception e) {
            log.warn("Failed to publish board: {}", e.getMessage());
        }
    }

    // --- touch ---

    public TouchHistory touchHistory() {
        return touchHistory;
    }

    public void recordTouch(Position p) {
        touchHistory.record(p);
    }

    // --- score / health / combo / level ---

    public ScoreState score() {
        return score;
    }

    // --- timer / countdown ---

    public GameTimer timer() {
        return timer;
    }

    // --- path / pattern / random / neighbours ---

    public PathGenerator paths() {
        return pathGenerator;
    }

    public List<Position> orthogonalNeighbors(Position p) {
        return NeighborUtils.orthogonalNeighbors(width, height, p);
    }

    public List<Position> allNeighbors(Position p) {
        return NeighborUtils.allNeighbors(width, height, p);
    }

    // --- events ---

    public void addListener(GameEventListener listener) {
        listeners.add(listener);
    }

    public void fire(GameEventListener.GameEvent event) {
        for (GameEventListener l : listeners) {
            try {
                l.onEvent(event);
            } catch (Exception e) {
                log.warn("GameEventListener failed: {}", e.getMessage());
            }
        }
    }

    public void win(String message) {
        score.recordWin();
        fire(GameEventListener.GameEvent.win(message));
    }

    public void lose(String message) {
        score.recordLoss();
        fire(GameEventListener.GameEvent.loss(message));
    }

    // --- lifecycle ---

    public boolean isActive() {
        return active;
    }

    public void deactivate() {
        active = false;
        timer.stop();
    }

    /**
     * Convenience for colour games that use the shared {@link TileColor} palette.
     */
    @SuppressWarnings("unchecked")
    public static GameContext<TileColor> forColors(
            int width, int height, GameMode mode,
            TileGatewayClient gateway, Consumer<byte[]> broadcaster
    ) {
        return new GameContext<>(
                width, height, mode,
                TileColor.CODEC, TileColor.OFF,
                gateway, broadcaster
        );
    }
}
