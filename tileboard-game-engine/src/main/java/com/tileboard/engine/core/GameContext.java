package com.tileboard.engine.core;

import com.tileboard.engine.event.GameEventBus;

/**
 * The single object passed to every {@link GameLifecycle} method. It is the
 * game's window onto the engine: board I/O, built-in features, event
 * publishing and session control all go through here.
 *
 * <p>All methods are thread-safe (either delegating to thread-safe
 * subsystems or synchronised internally).
 */
public interface GameContext extends CoreGameContext, FeatureProvider {

    GameEventBus eventBus();
}