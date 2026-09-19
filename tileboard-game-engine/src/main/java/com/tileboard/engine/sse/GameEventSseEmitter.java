package com.tileboard.engine.sse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.tileboard.engine.event.GameEvent;
import com.tileboard.engine.event.GameEventBus;
import com.tileboard.engine.event.GameEventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

/**
 * Bridges the engine's {@link GameEventBus} to Spring's {@link SseEmitter}.
 *
 * <p>One instance is created per SSE client connection. It subscribes to the
 * shared event bus and pushes serialised {@link SseGameEvent} JSON to the
 * HTTP response stream. When the client disconnects (or any I/O error occurs)
 * the subscription is automatically removed and the emitter is completed.
 *
 * <h3>Usage in a Spring controller:</h3>
 * <pre>{@code
 * @GetMapping(value = "/games/{sessionId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
 * public SseEmitter streamEvents(@PathVariable String sessionId) {
 *     return GameEventSseEmitter.forSession(sessionId, eventBus, objectMapper);
 * }
 * }</pre>
 */
public final class GameEventSseEmitter {

    private static final Logger log = LoggerFactory.getLogger(GameEventSseEmitter.class);

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private GameEventSseEmitter() {
    }

    /**
     * Creates an emitter that streams all events for {@code sessionId}.
     */
    public static SseEmitter forSession(String sessionId, GameEventBus bus) {
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
        AtomicReference<Runnable> unsubscribeRef = new AtomicReference<>();

        // Subscribe first — bus is async so push() won't be called immediately
        Runnable unsubscribe = bus.subscribeSession(sessionId, event ->
                push(emitter, event, unsubscribeRef));

        unsubscribeRef.set(unsubscribe);  // guaranteed visible before any event fires

        emitter.onCompletion(unsubscribe);
        emitter.onTimeout(unsubscribe);
        emitter.onError(ex -> unsubscribe.run());
        return emitter;
    }

    /**
     * Creates an emitter that streams all events of a given type across all sessions.
     */
    public static SseEmitter forEventType(GameEventType type, GameEventBus bus) {
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
        AtomicReference<Runnable> unsubscribeRef = new AtomicReference<>();
        Runnable unsubscribe = bus.subscribe(type, event ->
                push(emitter, event, unsubscribeRef));

        unsubscribeRef.set(unsubscribe);
        emitter.onCompletion(unsubscribe);
        emitter.onTimeout(unsubscribe);
        emitter.onError(ex -> unsubscribe.run());

        return emitter;
    }

    /**
     * Creates an emitter that streams every engine event (global feed).
     */
    public static SseEmitter global(GameEventBus bus) {
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
        AtomicReference<Runnable> unsubscribeRef = new AtomicReference<>();

        Runnable unsubscribe = bus.subscribe(event ->
                push(emitter, event, unsubscribeRef));

        unsubscribeRef.set(unsubscribe);
        emitter.onCompletion(unsubscribe);
        emitter.onTimeout(unsubscribe);
        emitter.onError(ex -> unsubscribe.run());

        return emitter;
    }

    private static void push(SseEmitter emitter, GameEvent event, AtomicReference<Runnable> unsubscribeRef) {
        try {
            SseGameEvent dto = toDto(event);
            String json = MAPPER.writeValueAsString(dto);
            emitter.send(SseEmitter.event()
                    .id(event.id())
                    .name(dto.type().name())
                    .data(json));
        } catch (IOException e) {
            Runnable unsub = unsubscribeRef.get();
            if (unsub != null) unsub.run();
            emitter.completeWithError(e);
        }
    }

    private static SseGameEvent toDto(GameEvent event) {
        SseGameEventType sseType = switch (event.type()) {
            case TICK -> SseGameEventType.TICK;
            case BOARD_UPDATED -> SseGameEventType.BOARD_UPDATE;
            case SCORE_CHANGED -> SseGameEventType.SCORE_UPDATE;
            case SESSION_STARTED,
                 SESSION_FINISHED,
                 SESSION_STOPPED -> SseGameEventType.SESSION_LIFECYCLE;
            default -> SseGameEventType.GAME_STATE;
        };
        return new SseGameEvent(
                event.sessionId(), event.gameId(), sseType, event.payload(), event.occurredAt());
    }
}