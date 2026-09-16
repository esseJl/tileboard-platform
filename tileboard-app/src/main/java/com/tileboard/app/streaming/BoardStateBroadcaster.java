package com.tileboard.app.streaming;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Fans out board frames to any number of subscribed clients (e.g. a
 * dashboard UI showing a live mirror of the physical tile board) over
 * Server-Sent Events. Kept as its own interface so the transport
 * (SSE today, could be WebSocket tomorrow) is swappable without the game
 * engine or gateway wiring knowing about it.
 */
public interface BoardStateBroadcaster {

    /** Registers a new subscriber and returns the emitter it should be served on. */
    SseEmitter subscribe();

    /** Sends a row-major flat frame (as produced by {@code Board.toWireBytes}) to every subscriber. */
    void broadcast(byte[] flatBoardBytes);
}
