package com.tileboard.engine.core;


import com.tileboard.engine.feature.HealthSystem;
import com.tileboard.engine.feature.ReactionSpeedTracker;
import com.tileboard.engine.feature.TouchAnalyzer;
import com.tileboard.engine.feature.TouchHistory;

public interface PlayerFeedbackFeatures {
    HealthSystem health();

    ReactionSpeedTracker reactionSpeed();

    TouchHistory touchHistory();

    TouchAnalyzer touchAnalyzer();
}
