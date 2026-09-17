package com.tileboard.engine.core;

/**
 * Creates a fresh {@link Game} instance for each new session. Use this when
 * your game implementation needs per-session construction arguments (e.g. an
 * injected service). For stateless games a simple lambda suffices:
 *
 * <pre>{@code
 * GameFactory factory = MyGame::new;
 * }</pre>
 */
@FunctionalInterface
public interface GameFactory {

    Game create();
}