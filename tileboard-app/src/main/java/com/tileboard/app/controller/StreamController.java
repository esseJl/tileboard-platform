package com.tileboard.app.controller;

import com.tileboard.engine.spring.SseGameEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/stream")
public class StreamController {

    private final SseGameEventPublisher publisher;

    public StreamController(SseGameEventPublisher publisher) {
        this.publisher = publisher;
    }

    @GetMapping(path = "/board", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamAllEvents() {
        return publisher.global();
    }

    @GetMapping(path = "/board/{sessionId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamSessionEvents(@PathVariable String sessionId) {
        return publisher.forSession(sessionId);
    }
}
