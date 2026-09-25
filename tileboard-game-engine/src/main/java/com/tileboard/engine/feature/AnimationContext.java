package com.tileboard.engine.feature;

import com.tileboard.engine.model.TileColor;
import com.tileboard.serial.board.Board;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.function.Consumer;

/**
 * Everything a {@link BoardAnimation} needs to render frames, bundled so the interface
 * doesn't need to grow every time a new effect needs a new piece of state.
 */
public final class AnimationContext {
    private final int width;
    private final int height;
    private final Consumer<Board<TileColor>> publisher;
    private final Random rng;
    private final Map<String, Object> params;

    public AnimationContext(int width, int height, Consumer<Board<TileColor>> publisher, Random rng) {
        this(width, height, publisher, rng, Collections.emptyMap());
    }

    private AnimationContext(int width, int height, Consumer<Board<TileColor>> publisher,
                             Random rng, Map<String, Object> params) {
        this.width = width;
        this.height = height;
        this.publisher = publisher;
        this.rng = rng;
        this.params = params;
    }

    /**
     * Returns a new context sharing engine state but carrying extra per-invocation params.
     */
    public AnimationContext withParams(Map<String, Object> extra) {
        Map<String, Object> merged = new HashMap<>(params);
        merged.putAll(extra);
        return new AnimationContext(width, height, publisher, rng, merged);
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public Consumer<Board<TileColor>> publisher() {
        return publisher;
    }

    public Random rng() {
        return rng;
    }

    @SuppressWarnings("unchecked")
    public <T> T param(String key, T defaultValue) {
        Object v = params.get(key);
        return v == null ? defaultValue : (T) v;
    }
}