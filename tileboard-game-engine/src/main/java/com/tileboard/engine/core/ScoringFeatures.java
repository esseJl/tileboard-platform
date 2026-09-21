package com.tileboard.engine.core;

import com.tileboard.engine.feature.ComboTracker;
import com.tileboard.engine.feature.LevelSystem;
import com.tileboard.engine.feature.ScoreSystem;

public interface ScoringFeatures {
    ScoreSystem scores();

    ComboTracker combos();

    LevelSystem levels();
}
