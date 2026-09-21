package com.tileboard.engine.core;

import com.tileboard.engine.feature.GameTimer;
import com.tileboard.engine.feature.MemoryFeature;
import com.tileboard.engine.feature.RandomFeature;

public interface UtilityFeatures {
    RandomFeature random();

    MemoryFeature memory();

    GameTimer timer();
}
