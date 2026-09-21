package com.tileboard.engine.core;

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

    static FeatureBundle create(int w, int h, List<Player> players, String sessionId, Consumer<Board<TileColor>> publisher) {
        TouchHistory touchHistory = new TouchHistory(sessionId);
        return new FeatureBundle(
                new ScoreSystem(players), new HealthSystem(players), new LevelSystem(), new ComboTracker(),
                new GameTimer(), touchHistory, new TouchAnalyzer(touchHistory),
                new BoardFeature(w, h), new NeighborFinder(w, h, Adjacency.FOUR_WAY), new PatternMatcher(),
                new RandomFeature(w, h), new WaveGenerator(w, h, publisher), new MemoryFeature(),
                new ReactionSpeedTracker(), new GraphFeature(w, h), new AnimationSystem(w, h, publisher));
    }

    void closeAll() {
        waves.close();
        animations.shutdown();
    }
}
