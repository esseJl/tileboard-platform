package com.tileboard.engine.core;

import com.tileboard.engine.model.TileEvent;

/**
 * The contract every game must implement. Methods are called by the engine
 * at well-defined lifecycle points. All methods have default no-op
 * implementations so games only override what they need.
 *
 * <p>Every method is guaranteed to be called on the callback executor thread
 * configured for the underlying {@link com.tileboard.serial.gateway.TileGatewayClient},
 * <em>except</em> {@link #onStart}, which runs on whichever thread calls
 * {@link GameEngine#startGame}.
 */
public interface GameLifecycle {

    /**
     * Called once when the session starts. Implementations should initialise
     * their state, light up the board and register any timers via
     * {@link GameContext}.
     */
    void onStart(GameContext ctx);

    /**
     * Called for every touch event arriving from the hardware. The engine
     * calls this only while the session is {@link GameStatus#RUNNING}.
     */
    void onTileEvent(GameContext ctx, TileEvent event);

    /**
     * Called on every engine tick (configurable via
     * {@link com.tileboard.engine.spring.TileboardEngineProperties}).
     * Useful for animations, countdown updates and timeout checks.
     * Default: no-op.
     */
    default void onTick(GameContext ctx) {}

    /**
     * Called when the session finishes (win/loss/stop). The game should turn
     * off the board and release resources. Default: no-op.
     */
    default void onStop(GameContext ctx, GameResult result) {}

    /**
     * Called when an uncaught exception escapes {@link #onTileEvent} or
     * {@link #onTick}. The default implementation logs and stops the session.
     */
    default void onError(GameContext ctx, Throwable error) {
        ctx.stopSession();
    }
}