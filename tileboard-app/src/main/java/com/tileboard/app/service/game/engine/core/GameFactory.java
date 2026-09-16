package com.tileboard.app.service.game.engine.core;

import com.tileboard.app.service.game.engine.registry.GameRegistry;

/**
 * Spring-discovered factory that can produce a concrete {@link Game} instance.
 * Implementing classes are expected to be {@code @Component}s; the
 * {@link GameRegistry} collects them
 * automatically via constructor injection of {@code List<GameFactory>}.
 */
public interface GameFactory {

    /** Stable identifier used in REST paths and configuration. */
    String gameId();

    /** Human-readable metadata. */
    GameDefinition definition();

    /**
     * Creates a fresh game instance for the given mode and board geometry.
     * Must not retain mutable state across calls.
     */
    Game<?> create(GameMode mode, int width, int height);
}
