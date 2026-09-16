package com.tileboard.app.service.game.engine.web;

import com.tileboard.app.service.game.engine.core.GameDefinition;
import com.tileboard.app.service.game.engine.core.GameMode;
import com.tileboard.app.service.game.engine.registry.GameRegistry;
import com.tileboard.app.service.game.engine.session.GameSession;
import com.tileboard.app.service.game.engine.session.GameSessionManager;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Collection;

@RestController
@RequestMapping("/api/v1/games")
public class GameController {

    private final GameRegistry registry;
    private final GameSessionManager sessionManager;

    public GameController(GameRegistry registry, GameSessionManager sessionManager) {
        this.registry = registry;
        this.sessionManager = sessionManager;
    }

    @GetMapping
    public Collection<GameDefinition> list() {
        return registry.listDefinitions();
    }

    @GetMapping("/session")
    public SessionResponse session() {
        return sessionManager.current()
                .map(SessionResponse::from)
                .orElse(SessionResponse.none());
    }

    @PostMapping("/{gameId}/start")
    @ResponseStatus(HttpStatus.CREATED)
    public SessionResponse start(
            @PathVariable String gameId,
            @Valid @RequestBody StartGameRequest request
    ) {
        GameSession session = sessionManager.start(gameId, request.mode());
        return SessionResponse.from(session);
    }

    @PostMapping("/stop")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void stop() {
        sessionManager.stop();
    }

    public record StartGameRequest(@NotNull GameMode mode) {
    }

    public record SessionResponse(
            boolean active,
            String gameId,
            String displayName,
            GameMode mode,
            Instant startedAt
    ) {
        static SessionResponse none() {
            return new SessionResponse(false, null, null, null, null);
        }

        static SessionResponse from(GameSession s) {
            return new SessionResponse(
                    true,
                    s.gameId(),
                    s.definition().displayName(),
                    s.mode(),
                    s.startedAt()
            );
        }
    }
}
