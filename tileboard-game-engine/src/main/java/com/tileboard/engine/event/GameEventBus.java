package com.tileboard.engine.event;

/**
 * In-process, topic-free event bus for engine-level events. All sessions
 * share one instance so SSE emitters and monitoring code can subscribe once
 * and observe every session.
 */
public interface GameEventBus {

    void publish(GameEvent event);

    /**
     * Subscribes {@code listener} to all future events. Returns a
     * {@link Runnable} that, when invoked, removes the subscription.
     */
    Runnable subscribe(GameEventListener listener);

    /** Subscribes only for events of {@code type}. */
    Runnable subscribe(GameEventType type, GameEventListener listener);

    /** Subscribes only for events belonging to {@code sessionId}. */
    Runnable subscribeSession(String sessionId, GameEventListener listener);
}