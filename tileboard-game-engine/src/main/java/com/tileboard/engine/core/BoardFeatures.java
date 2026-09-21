package com.tileboard.engine.core;

import com.tileboard.engine.feature.BoardFeature;
import com.tileboard.engine.feature.GraphFeature;
import com.tileboard.engine.feature.PatternMatcher;
import com.tileboard.engine.feature.neighbor.NeighborFinder;

public interface BoardFeatures {
    BoardFeature board();

    NeighborFinder neighbors();

    PatternMatcher patterns();

    GraphFeature graph();
}
