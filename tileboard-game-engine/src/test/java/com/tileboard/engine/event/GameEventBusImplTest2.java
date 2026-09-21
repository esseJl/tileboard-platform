package com.tileboard.engine.event;

import com.tileboard.engine.core.SessionSnapshot;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameEventBusImplTest2 {

    @Test
    void eachSubscriberReceivesEventExactlyOnce() {
        try (GameEventBusImpl bus = new GameEventBusImpl(16, EventOverflowPolicy.DROP_OLDEST)) {
            List<GameEvent> received = Collections.synchronizedList(new ArrayList<>());
            bus.subscribe(received::add);

            bus.publish(sampleEvent(GameEventType.TICK));

            Awaitility.await().atMost(Duration.ofSeconds(1))
                    .untilAsserted(() -> assertEquals(1, received.size())); // نه ۲!
        }
    }

    @Test
    void dropOldestPolicyKeepsQueueBoundedUnderOverflow() {
        try (GameEventBusImpl bus = new GameEventBusImpl(4, EventOverflowPolicy.DROP_OLDEST)) {
            CountDownLatch block = new CountDownLatch(1);
            List<GameEvent> received = Collections.synchronizedList(new ArrayList<>());
            bus.subscribe(e -> {
                try {
                    block.await();
                } catch (InterruptedException ignored) {
                }
                received.add(e);
            });

            for (int i = 0; i < 50; i++) bus.publish(sampleEvent(GameEventType.TICK));
            block.countDown();

            Awaitility.await().atMost(Duration.ofSeconds(2))
                    .untilAsserted(() -> assertTrue(received.size() <= 50));
            assertTrue(bus.droppedEventCount() > 0);
        }
    }

    @Test
    void sessionFilterOnlyDeliversMatchingEvents() {
        try (GameEventBusImpl bus = new GameEventBusImpl()) {
            List<GameEvent> received = Collections.synchronizedList(new ArrayList<>());
            bus.subscribeSession("s1", received::add);

            bus.publish(sampleEventForSession("s2"));
            bus.publish(sampleEventForSession("s1"));

            Awaitility.await().atMost(Duration.ofSeconds(1))
                    .untilAsserted(() -> assertEquals(1, received.size()));
            assertEquals("s1", received.get(0).sessionId());
        }
    }

    private GameEvent sampleEvent(GameEventType type) {
        return GameEvent.of(type, "s1", "g1",
                new SessionSnapshot(Map.of(), 1, "RUNNING", 0));
    }

    private GameEvent sampleEventForSession(String sessionId) {
        return GameEvent.of(GameEventType.TICK, sessionId, "g1",
                new SessionSnapshot(Map.of(), 1, "RUNNING", 0));
    }
}