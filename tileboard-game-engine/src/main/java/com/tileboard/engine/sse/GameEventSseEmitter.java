package com.tileboard.engine.sse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.tileboard.engine.event.GameEvent;
import com.tileboard.engine.event.GameEventBusImpl;
import com.tileboard.engine.event.GameEventBusImpl.OverflowPolicy;
import com.tileboard.engine.event.GameEventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Bridges the engine's {@link GameEventBusImpl} to Spring's {@link SseEmitter}.
 *
 * <p>Every network write ({@code emitter.send}) happens on a dedicated
 * per-connection subscriber thread (see {@link GameEventBusImpl#subscribe(
 *com.tileboard.engine.event.GameEventListener, GameEventType, String, int,
 * OverflowPolicy)}), never on the shared event-dispatch thread used by other
 * subscribers or sessions. The subscriber's queue uses {@code DROP_OLDEST}
 * with a small capacity: a stalled browser tab loses a few intermediate
 * ticks/board-updates rather than causing unbounded memory growth or stalling
 * anyone else.
 */
public final class GameEventSseEmitter {

    private static final Logger log = LoggerFactory.getLogger(GameEventSseEmitter.class);
    private static final int PER_CLIENT_QUEUE_CAPACITY = 32;
    private static final long HEARTBEAT_SECONDS = 15;

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private GameEventSseEmitter() {
    }

    public static SseEmitter forSession(String sessionId, GameEventBusImpl bus, ScheduledExecutorService heartbeats) {
        return build(bus, null, sessionId, heartbeats);
    }

    public static SseEmitter forEventType(GameEventType type, GameEventBusImpl bus, ScheduledExecutorService heartbeats) {
        return build(bus, type, null, heartbeats);
    }

    public static SseEmitter global(GameEventBusImpl bus, ScheduledExecutorService heartbeats) {
        return build(bus, null, null, heartbeats);
    }

    private static SseEmitter build(GameEventBusImpl bus, GameEventType type, String sessionId,
                                    ScheduledExecutorService heartbeats) {
        SseEmitter emitter = new SseEmitter(0L); // no server-side timeout; rely on heartbeat + client disconnect
        AtomicReference<Runnable> unsubscribeRef = new AtomicReference<>();
        AtomicReference<ScheduledFuture<?>> heartbeatRef = new AtomicReference<>();

        Runnable teardown = () -> {
            Runnable unsub = unsubscribeRef.get();
            if (unsub != null) unsub.run();
            ScheduledFuture<?> hb = heartbeatRef.get();
            if (hb != null) hb.cancel(false);
        };

        Runnable unsubscribe = bus.subscribe(
                event -> push(emitter, event, teardown),
                type, sessionId,
                PER_CLIENT_QUEUE_CAPACITY, OverflowPolicy.DROP_OLDEST);
        unsubscribeRef.set(unsubscribe);

        ScheduledFuture<?> heartbeat = heartbeats.scheduleAtFixedRate(
                () -> sendComment(emitter, teardown), HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
        heartbeatRef.set(heartbeat);

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
            // IllegalStateException covers "already completed" races on disconnect.
            teardown.run();
            emitter.completeWithError(e);
        }
    }

    /**
     * Keeps intermediary proxies/load-balancers from closing idle SSE connections, and detects dead clients fast.
     */
    private static void sendComment(SseEmitter emitter, Runnable teardown) {
        try {
            emitter.send(SseEmitter.event().comment("ping"));
        } catch (IOException | IllegalStateException e) {
            teardown.run();
            emitter.completeWithError(e);
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
}