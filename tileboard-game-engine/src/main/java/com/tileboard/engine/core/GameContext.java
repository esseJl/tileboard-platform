package com.tileboard.engine.core;

import com.tileboard.engine.event.GameEventBus;
import com.tileboard.engine.feature.*;
import com.tileboard.engine.feature.neighbor.NeighborFinder;
import com.tileboard.engine.model.Player;
import com.tileboard.engine.model.TileColor;
import com.tileboard.serial.board.Board;

import java.util.List;

/**
 * The single object passed to every {@link GameLifecycle} method. It is the
 * game's window onto the engine: board I/O, built-in features, event
 * publishing and session control all go through here.
 *
 * <p>All methods are thread-safe (either delegating to thread-safe
 * subsystems or synchronised internally).
 */
public interface GameContext {

    // ── Session info ──────────────────────────────────────────────────────

    String sessionId();
    GameDescriptor descriptor();
    List<Player> players();
    GameState state();

    // ── Board I/O ─────────────────────────────────────────────────────────

    int boardWidth();
    int boardHeight();

    /**
     * Sends a full board snapshot to the hardware. Thread-safe – multiple
     * callers can call this concurrently; the underlying write is serialised.
     */
    void publishBoard(Board<TileColor> board);

    /**
     * Convenience: sets a single tile on the hardware without managing a
     * full Board object.
     */
    void setTile(int row, int col, TileColor color);

    /** Fills all tiles with {@code color} and sends to the hardware. */
    void fillBoard(TileColor color);

    /** Returns a fresh blank board pre-filled with {@link TileColor#OFF}. */
    Board<TileColor> newBoard();

    // ── Built-in features ────────────────────────────────────────────────

    ScoreSystem     scores();
    HealthSystem    health();
    LevelSystem     levels();
    ComboTracker    combos();
    GameTimer       timer();
    TouchHistory    touchHistory();
    TouchAnalyzer   touchAnalyzer();
    BoardFeature    board();
    NeighborFinder  neighbors();
    PatternMatcher  patterns();
    RandomFeature   random();
    WaveGenerator   waves();
    MemoryFeature   memory();
    ReactionSpeedTracker reactionSpeed();
    GraphFeature    graph();
    /**
     * Animation system for different game states
     */
    AnimationSystem animations();

    // ── Event bus ────────────────────────────────────────────────────────

    GameEventBus eventBus();

    // ── Session control ───────────────────────────────────────────────────

    /** Marks the session as finished with the given winners. */
    void winSession(List<Player> winners);

    /** Marks the session as finished with no winner (draw / loss). */
    void loseSession();

    /** Immediately stops the session (equivalent to abort). */
    void stopSession();

    /** Returns the current status of this session. */
    GameStatus status();
}