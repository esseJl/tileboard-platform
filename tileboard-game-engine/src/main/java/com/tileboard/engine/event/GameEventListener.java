package com.tileboard.engine.event;

/**
 * Notified for every {@link GameEvent} published on the {@link GameEventBus}.
 */
@FunctionalInterface
public interface GameEventListener {

    void onEvent(GameEvent event);
}