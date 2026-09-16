package com.tileboard.app.gameengine;

/**
 * Constructs a {@link Game} instance for a given {@link GameMode} and board
 * geometry, and advertises the {@link GameDefinition} it builds.
 *
 * <p>Every concrete game contributes exactly one Spring-managed
 * {@code @Component} implementing this interface. {@link GameRegistry}
 * auto-discovers every such bean, so registering a new game is purely
 * additive: no existing class (registry, session manager, controller) is
 * ever touched.
 */
public interface GameFactory {

    /** Stable identifier used in URLs and as the registry key, e.g. {@code "tic-tac-toe"}. */
    String gameId();

    GameDefinition definition();

    /** Builds a fresh {@link Game} instance ready to be started for a new session. */
    Game<?> create(GameMode mode, int width, int height);
}
