package com.tileboard.engine.core;

import java.util.List;
import java.util.Optional;

/**
 * Catalog of all registered {@link Game} types. Auto-populated by the Spring
 * Boot adapter when {@link Game} beans are on the application context; can
 * also be populated manually for non-Spring usage.
 */
public interface GameRegistry {

    /** Registers a game type. Idempotent for the same {@code gameId}. */
    void register(Game game);

    /** Registers a factory-based game type. */
    void register(GameDescriptor descriptor, GameFactory factory);

    Optional<GameDescriptor> find(String gameId);

    List<GameDescriptor> listAll();

    /** Creates a fresh {@link Game} instance for the given id. */
    Game instantiate(String gameId);

    boolean isRegistered(String gameId);
}