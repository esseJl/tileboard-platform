package com.tileboard.engine.core;

import com.tileboard.engine.feature.AnimationSystem;
import com.tileboard.engine.feature.WaveGenerator;

public interface PresentationFeatures {
    WaveGenerator waves();

    AnimationSystem animations();
}
