package com.tileboard.engine.core;

/**
 * The single interface every game must implement. Combines static metadata
 * ({@link GameDescriptor}) with the runtime lifecycle ({@link GameLifecycle}).
 *
 * <p>A {@code Game} instance is <em>stateless</em>: each session creates its
 * own {@link GameSession}, and mutable state lives in {@link GameState} (via
 * {@link GameContext}). This allows the engine to run multiple concurrent
 * sessions of the same game type without synchronisation issues.
 *
 * <p><strong>Auto-registration:</strong> In the Spring Boot layer any
 * {@code @Component}-annotated {@code Game} bean is automatically discovered
 * and added to the {@link GameRegistry} by
 * {@link com.tileboard.engine.spring.TileboardEngineAutoConfiguration}.
 * Outside Spring, call {@link GameRegistry#register(Game)} manually.
 */
public interface Game extends GameLifecycle {

    /** Static metadata for this game type. Never {@code null}. */
    GameDescriptor descriptor();
}