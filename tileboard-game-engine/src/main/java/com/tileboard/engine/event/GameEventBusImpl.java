package com.tileboard.engine.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Thread-safe, non-blocking {@link GameEventBus} implementation. Listeners
 * run on a dedicated single-thread executor so publishing never blocks the
 * serial reader or the game loop.
 */
public final class GameEventBusImpl implements GameEventBus {

    private static final Logger log = LoggerFactory.getLogger(GameEventBusImpl.class);

    private record Subscription(GameEventListener listener,
                                GameEventType filterType,
                                String filterSessionId) {}

    private final CopyOnWriteArrayList<Subscription> subscriptions = new CopyOnWriteArrayList<>();
    private final Executor executor;

    public GameEventBusImpl() {
        this(Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "tileboard-eventbus");
            t.setDaemon(true);
            return t;
        }));
    }

    public GameEventBusImpl(Executor executor) {
        this.executor = Objects.requireNonNull(executor);
    }

    @Override
    public void publish(GameEvent event) {
        Objects.requireNonNull(event);
        executor.execute(() -> {
            for (Subscription sub : subscriptions) {
                if (matches(sub, event)) {
                    try {
                        sub.listener().onEvent(event);
                    } catch (RuntimeException e) {
                        log.warn("Event listener threw while handling {}", event.type(), e);
                    }
                }
            }
        });
    }

    @Override
    public Runnable subscribe(GameEventListener listener) {
        Subscription sub = new Subscription(listener, null, null);
        subscriptions.add(sub);
        return () -> subscriptions.remove(sub);
    }

    @Override
    public Runnable subscribe(GameEventType type, GameEventListener listener) {
        Subscription sub = new Subscription(listener, type, null);
        subscriptions.add(sub);
        return () -> subscriptions.remove(sub);
    }

    @Override
    public Runnable subscribeSession(String sessionId, GameEventListener listener) {
        Subscription sub = new Subscription(listener, null, sessionId);
        subscriptions.add(sub);
        return () -> subscriptions.remove(sub);
    }

    private boolean matches(Subscription sub, GameEvent event) {
        if (sub.filterType() != null && sub.filterType() != event.type()) return false;
        if (sub.filterSessionId() != null && !sub.filterSessionId().equals(event.sessionId())) return false;
        return true;
    }
}