package com.tileboard.engine.spring;

import com.tileboard.engine.event.GameEventBusImpl;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

public final class GameEngineMetricsBinder {
    public GameEngineMetricsBinder(MeterRegistry registry, GameEngineManager manager, GameEventBusImpl bus) {
        Gauge.builder("tileboard.engine.active_sessions", manager,
                        m -> m.current().map(e -> e.activeSessions().size()).orElse(0))
                .description("Number of currently active game sessions")
                .register(registry);

        Gauge.builder("tileboard.eventbus.dropped_events", bus, GameEventBusImpl::droppedEventCount)
                .description("Total events dropped due to slow subscribers")
                .register(registry);

        Gauge.builder("tileboard.eventbus.subscribers", bus, GameEventBusImpl::subscriberCount)
                .description("Current number of active event bus subscriptions")
                .register(registry);
    }
}
