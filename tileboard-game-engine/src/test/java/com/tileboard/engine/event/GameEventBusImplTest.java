package com.tileboard.engine.event;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class GameEventBusImplTest {

    @Test
    void subscriptionsFilterAndUnsubscribeAndListenerFailureIsIsolated() {
        GameEventBusImpl bus = new GameEventBusImpl(Runnable::run);
        AtomicInteger all = new AtomicInteger();
        AtomicInteger type = new AtomicInteger();
        AtomicInteger session = new AtomicInteger();

        Runnable unsubAll = bus.subscribe(e -> all.incrementAndGet());
        bus.subscribe(GameEventType.SESSION_STARTED, e -> type.incrementAndGet());
        bus.subscribeSession("s1", e -> session.incrementAndGet());
        bus.subscribe(e -> { throw new RuntimeException("boom"); });

        bus.publish(GameEvent.of(GameEventType.SESSION_STARTED, "s1", "g", Map.of("x", 1)));
        assertEquals(1, all.get());
        assertEquals(1, type.get());
        assertEquals(1, session.get());

        unsubAll.run();
        bus.publish(GameEvent.of(GameEventType.SESSION_STOPPED, "s2", "g", Map.of()));
        assertEquals(1, all.get());
        assertEquals(1, type.get());
        assertEquals(1, session.get());
    }

    @Test
    void eventPayloadIsDefensivelyCopied() {
        var mutable = new java.util.HashMap<String,Object>();
        mutable.put("score", 1);
        GameEvent event = GameEvent.of(GameEventType.SCORE_CHANGED, "s", "g", mutable);
        mutable.put("score", 99);
        assertEquals(1, event.payload().get("score"));
        assertThrows(UnsupportedOperationException.class, () -> event.payload().put("x", 1));
    }
}
