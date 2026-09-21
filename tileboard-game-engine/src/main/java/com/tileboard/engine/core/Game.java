package com.tileboard.engine.core;


/**
 * The contract every game must implement.
 *
 * <h2>Statelessness contract</h2>
 * When registered via {@link GameRegistry#register(Game)}, a single {@code Game}
 * instance is reused across every future session of that {@code gameId} (similar to
 * the Servlet singleton model). Implementations MUST NOT keep per-session mutable
 * state in instance fields; all session-scoped data must be stored through
 * {@link GameContext#state()} (a fresh {@link GameState} is created per session) or
 * through the feature objects exposed by {@link GameContext}, which are also
 * per-session.
 * <p>
 * If a game genuinely needs per-instance construction state (e.g. injected
 * dependencies configured differently per session), register it with
 * {@link GameRegistry#register(GameDescriptor, GameFactory)} instead, which creates
 * a brand-new instance for every session.
 */
public interface Game extends GameLifecycle {

    /**
     * Static metadata for this game type. Never {@code null}.
     */
    GameDescriptor descriptor();
}