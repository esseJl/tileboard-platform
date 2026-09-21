package com.tileboard.engine.sse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.tileboard.engine.event.EventOverflowPolicy;
import com.tileboard.engine.event.GameEvent;
import com.tileboard.engine.event.GameEventBus;
import com.tileboard.engine.event.GameEventType;
import com.tileboard.engine.event.SubscriptionOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class GameEventSseEmitter {

    private static final Logger log = LoggerFactory.getLogger(GameEventSseEmitter.class);
    private static final int PER_CLIENT_QUEUE_CAPACITY = 32;
    private static final long HEARTBEAT_SECONDS = 15;

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private GameEventSseEmitter() {
    }

    public static SseEmitter forSession(String sessionId, GameEventBus bus, ScheduledExecutorService heartbeats) {
        return build(bus, null, sessionId, heartbeats);
    }

    public static SseEmitter forEventType(GameEventType type, GameEventBus bus, ScheduledExecutorService heartbeats) {
        return build(bus, type, null, heartbeats);
    }

    public static SseEmitter global(GameEventBus bus, ScheduledExecutorService heartbeats) {
        return build(bus, null, null, heartbeats);
    }

    public static SseEmitter build(GameEventBus bus, GameEventType type, String sessionId,
                                   ScheduledExecutorService heartbeats) {
        SseEmitter emitter = new SseEmitter(0L);

        AtomicReference<Runnable> unsubscribeRef = new AtomicReference<>();
        AtomicReference<ScheduledFuture<?>> heartbeatRef = new AtomicReference<>();

        Runnable teardown = () -> {
            Runnable unsub = unsubscribeRef.getAndSet(null);
            if (unsub != null) unsub.run();
            ScheduledFuture<?> hb = heartbeatRef.getAndSet(null);
            if (hb != null) hb.cancel(false);
        };

        SubscriptionOptions options = SubscriptionOptions.defaults(PER_CLIENT_QUEUE_CAPACITY)
                .withPolicy(EventOverflowPolicy.DROP_OLDEST);
        if (type != null) options = options.withType(type);
        if (sessionId != null) options = options.withSession(sessionId);

        unsubscribeRef.set(bus.subscribe(event -> push(emitter, event, teardown), options));

        heartbeatRef.set(heartbeats.scheduleAtFixedRate(
                () -> sendComment(emitter, teardown), HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS));

        emitter.onCompletion(teardown);
        emitter.onTimeout(teardown);
        emitter.onError(ex -> teardown.run());

        return emitter;
    }

    private static void push(SseEmitter emitter, GameEvent event, Runnable teardown) {
        try {
            SseGameEvent dto = toDto(event);
            String json = MAPPER.writeValueAsString(dto);
            emitter.send(SseEmitter.event().id(event.id()).name(dto.type().name()).data(json));
        } catch (IOException | IllegalStateException e) {
            teardown.run();
            safeCompleteWithError(emitter, e);
        }
    }

    private static void sendComment(SseEmitter emitter, Runnable teardown) {
        try {
            emitter.send(SseEmitter.event().comment("ping"));
        } catch (IOException | IllegalStateException e) {
            teardown.run();
            safeCompleteWithError(emitter, e);
        }
    }

    private static SseGameEvent toDto(GameEvent event) {
        SseGameEventType sseType = switch (event.type()) {
            case TICK -> SseGameEventType.TICK;
            case BOARD_UPDATED -> SseGameEventType.BOARD_UPDATE;
            case SCORE_CHANGED -> SseGameEventType.SCORE_UPDATE;
            case SESSION_STARTED, SESSION_FINISHED, SESSION_STOPPED -> SseGameEventType.SESSION_LIFECYCLE;
            default -> SseGameEventType.GAME_STATE;
        };
        return new SseGameEvent(event.sessionId(), event.gameId(), sseType, event.payload(), event.occurredAt());
    }

    private static void safeCompleteWithError(SseEmitter emitter, Exception e) {
        try {
            emitter.completeWithError(e);
        } catch (IllegalStateException ignored) {
            // already completed
        }
    }
}