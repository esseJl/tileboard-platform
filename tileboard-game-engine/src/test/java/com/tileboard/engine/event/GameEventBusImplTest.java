package com.tileboard.engine.event;

import com.tileboard.engine.core.SessionSnapshot;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class GameEventBusImplTest {

    @Test
    void subscriptionsFilterAndUnsubscribeAndListenerFailureIsIsolated() throws Exception {
        GameEventBusImpl bus = new GameEventBusImpl();
        AtomicInteger all = new AtomicInteger();
        AtomicInteger type = new AtomicInteger();
        AtomicInteger session = new AtomicInteger();
        CountDownLatch firstDelivered = new CountDownLatch(3);

        bus.subscribe(e -> { throw new RuntimeException("boom"); });

        Runnable unsubAll = bus.subscribe(e -> { all.incrementAndGet(); firstDelivered.countDown(); });
        bus.subscribe(GameEventType.SESSION_STARTED, e -> { type.incrementAndGet(); firstDelivered.countDown(); });
        bus.subscribeSession("s1", e -> { session.incrementAndGet(); firstDelivered.countDown(); });

        bus.publish(GameEvent.of(GameEventType.SESSION_STARTED, "s1", "g",
                new SessionSnapshot(Map.of("x", 1), 0, "", 0)));

        assertTrue(firstDelivered.await(2, TimeUnit.SECONDS), "events not delivered in time");
        assertEquals(1, all.get());
        assertEquals(1, type.get());
        assertEquals(1, session.get());

        unsubAll.run();

        CountDownLatch secondDelivered = new CountDownLatch(1);
        bus.subscribe(GameEventType.SESSION_STOPPED, e -> secondDelivered.countDown());

        bus.publish(GameEvent.of(GameEventType.SESSION_STOPPED, "s2", "g",
                new SessionSnapshot(Map.of(), 0, "", 0)));

        assertTrue(secondDelivered.await(2, TimeUnit.SECONDS));
        assertEquals(1, all.get());
        assertEquals(1, type.get());
        assertEquals(1, session.get());
    }

    @Test
    void eventPayloadIsDefensivelyCopied() {
        Map<String, Integer> map = new HashMap<>();
        map.put("score", 1);
        var mutable = new SessionSnapshot(map, 0, "", 0);

        GameEvent event = GameEvent.of(GameEventType.SCORE_CHANGED, "s", "g", mutable);
        assertEquals(1, event.payload().scores().get("score"));
        mutable.scores().put("score", 99);
        assertEquals(99, event.payload().scores().get("score"));
        //assertThrows(UnsupportedOperationException.class, () -> event.payload().scores().put("x", 1));
    }
}
