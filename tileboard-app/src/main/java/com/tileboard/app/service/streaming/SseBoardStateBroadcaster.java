package com.tileboard.app.service.streaming;

import com.tileboard.engine.codec.ColorTileCodec;
import com.tileboard.engine.core.BoardFrameBroadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class SseBoardStateBroadcaster implements BoardStateBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(SseBoardStateBroadcaster.class);
    private static final String EVENT_NAME = "board-frame";

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    public SseBoardStateBroadcaster(BoardFrameBroadcaster boardFrameBroadcaster) {
        boardFrameBroadcaster.subscribe((sessionId, board) ->
                broadcast(board.toWireBytes(ColorTileCodec.instance())));
    }

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
        if (emitters.isEmpty()) return;
        int[] unsignedTiles = toUnsignedInts(flatBoardBytes);
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name(EVENT_NAME).data(unsignedTiles));
            } catch (IOException | RuntimeException e) {
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