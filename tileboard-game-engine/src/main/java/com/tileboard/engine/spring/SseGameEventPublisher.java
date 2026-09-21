package com.tileboard.engine.spring;


import com.tileboard.engine.event.GameEventBus;
import com.tileboard.engine.event.GameEventType;
import com.tileboard.engine.sse.GameEventSseEmitter;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.ScheduledExecutorService;

@Component
public class SseGameEventPublisher {

    private final GameEventBus eventBus;
    private final ScheduledExecutorService heartbeats;

    public SseGameEventPublisher(GameEventBus eventBus, ScheduledExecutorService tileboardSseHeartbeatScheduler) {
        this.eventBus = eventBus;
        this.heartbeats = tileboardSseHeartbeatScheduler;
    }

    public SseEmitter forSession(String sessionId) {
        return GameEventSseEmitter.build(eventBus, null, sessionId, heartbeats);
    }

    public SseEmitter forEventType(GameEventType type) {
        return GameEventSseEmitter.build(eventBus, type, null, heartbeats);
    }

    public SseEmitter global() {
        return GameEventSseEmitter.build(eventBus, null, null, heartbeats);
    }
}
