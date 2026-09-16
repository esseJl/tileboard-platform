package com.tileboard.app.service.streaming;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * {@link BoardStateBroadcaster} backed by {@link SseEmitter}. Emitters are
 * never bounded to a single "current" subscriber (unlike the legacy
 * implementation this replaces) - any number of dashboards can watch the
 * board at once, and a broken/timed-out connection is pruned automatically
 * instead of taking the whole feature down.
 */
@Service
public class SseBoardStateBroadcaster implements BoardStateBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(SseBoardStateBroadcaster.class);
    private static final String EVENT_NAME = "board-frame";

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    @Override
    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(ex -> emitters.remove(emitter));
        emitters.add(emitter);
        return emitter;
    }

    @Override
    public void broadcast(byte[] flatBoardBytes) {
        if (emitters.isEmpty()) {
            return;
        }
        int[] unsignedTiles = toUnsignedInts(flatBoardBytes);
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name(EVENT_NAME).data(unsignedTiles));
            } catch (IOException | RuntimeException e) {
                // RuntimeException (e.g. IllegalStateException from an emitter
                // that already completed/timed out a moment ago) is caught
                // too, not just IOException - otherwise one stale subscriber
                // throwing here would abort this loop and every OTHER,
                // perfectly healthy subscriber would silently miss this frame.
                log.debug("Dropping a dead SSE subscriber", e);
                emitters.remove(emitter);
            }
        }
    }

    private int[] toUnsignedInts(byte[] flatBoardBytes) {
        int[] result = new int[flatBoardBytes.length];
        for (int i = 0; i < flatBoardBytes.length; i++) {
            result[i] = flatBoardBytes[i] & 0xFF;
        }
        return result;
    }
}
