package com.tileboard.engine.core;

import com.tileboard.engine.feature.*;
import com.tileboard.engine.feature.neighbor.NeighborFinder;

public interface FeatureProvider {
    ScoreSystem scores();

    HealthSystem health();

    LevelSystem levels();

    ComboTracker combos();

    GameTimer timer();

    TouchHistory touchHistory();

    TouchAnalyzer touchAnalyzer();

    BoardFeature board();

    NeighborFinder neighbors();

    PatternMatcher patterns();

    RandomFeature random();

    WaveGenerator waves();

    MemoryFeature memory();

    ReactionSpeedTracker reactionSpeed();

    GraphFeature graph();

    AnimationSystem animations();
}
