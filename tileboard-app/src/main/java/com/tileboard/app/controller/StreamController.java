package com.tileboard.app.controller;

import com.tileboard.app.service.streaming.BoardStateBroadcaster;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/stream")
public class StreamController {

    private final BoardStateBroadcaster broadcaster;

    public StreamController(BoardStateBroadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }

    /** Live mirror of whatever is currently being sent to the physical board. */
    @GetMapping(path = "/board", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamBoardState() {
        return broadcaster.subscribe();
    }
}
