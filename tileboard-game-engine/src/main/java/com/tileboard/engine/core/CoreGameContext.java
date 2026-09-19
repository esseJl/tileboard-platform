package com.tileboard.engine.core;

import com.tileboard.engine.event.GameEventBus;
import com.tileboard.engine.feature.ScoreSystem;


public interface CoreGameContext extends BoardContext, SessionControl {
    GameEventBus eventBus();

    ScoreSystem scores();
}
