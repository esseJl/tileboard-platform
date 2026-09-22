package com.tileboard.engine.core;

import com.tileboard.engine.event.GameEventType;
import com.tileboard.engine.feature.*;
import com.tileboard.engine.feature.neighbor.Adjacency;
import com.tileboard.engine.feature.neighbor.NeighborFinder;
import com.tileboard.engine.model.Player;
import com.tileboard.engine.model.TileColor;
import com.tileboard.serial.board.Board;

import java.util.List;
import java.util.function.Consumer;

public record FeatureBundle(
        ScoreSystem scores, HealthSystem health, LevelSystem levels, ComboTracker combos,
        GameTimer timer, TouchHistory touchHistory, TouchAnalyzer touchAnalyzer,
        BoardFeature board, NeighborFinder neighbors, PatternMatcher patterns,
        RandomFeature random, WaveGenerator waves, MemoryFeature memory,
        ReactionSpeedTracker reactionSpeed, GraphFeature graph, AnimationSystem animations) {

    /**
     * @param eventNotifier called whenever a feature system's state actually changes
     *                      (score, health, level, combo, timer expiry) so the engine can
     *                      publish the matching {@link GameEventType} on the shared
     *                      {@code GameEventBus} for SSE subscribers. Board changes go
     *                      through {@code publisher} instead, as before.
     */
    static FeatureBundle create(int w, int h, List<Player> players, String sessionId,
                                int touchHistoryMaxSize, Consumer<Board<TileColor>> publisher,
                                Consumer<GameEventType> eventNotifier) {
        TouchHistory touchHistory = new TouchHistory(sessionId, touchHistoryMaxSize);
        return new FeatureBundle(
                new ScoreSystem(players, () -> eventNotifier.accept(GameEventType.SCORE_CHANGED)),
                new HealthSystem(players, 3, () -> eventNotifier.accept(GameEventType.HEALTH_CHANGED)),
                new LevelSystem(() -> eventNotifier.accept(GameEventType.LEVEL_UP)),
                new ComboTracker(() -> eventNotifier.accept(GameEventType.COMBO_HIT)),
                new GameTimer(() -> eventNotifier.accept(GameEventType.TIMER_EXPIRED)),
                touchHistory, new TouchAnalyzer(touchHistory),
                new BoardFeature(w, h), new NeighborFinder(w, h, Adjacency.FOUR_WAY), new PatternMatcher(),
                new RandomFeature(w, h), new WaveGenerator(w, h, publisher), new MemoryFeature(),
                new ReactionSpeedTracker(), new GraphFeature(w, h), new AnimationSystem(w, h, publisher));
    }

    void closeAll() {
        waves.close();
        animations.shutdown();
    }
}