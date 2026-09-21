package com.tileboard.engine.feature;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public final class AnimationRegistry {
    private final Map<String, BoardAnimation> animations = new ConcurrentHashMap<>();

/*    public AnimationRegistry registerDefaults() {
        register("win.radial-burst", AnimationLibrary::radialBurst);
        register("lose.fade-to-red", AnimationLibrary::fadeToRed);
        register("standby.breathing", AnimationLibrary::breathing);
        return this;
    }*/

    public void register(String key, BoardAnimation animation) {
        animations.put(key, Objects.requireNonNull(animation));
    }

    public BoardAnimation require(String key) {
        BoardAnimation a = animations.get(key);
        if (a == null) throw new IllegalArgumentException("No animation registered under key: " + key);
        return a;
    }
}
