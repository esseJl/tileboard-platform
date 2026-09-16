package com.tileboard.app.gameengine;

import com.tileboard.app.gameengine.dto.GameDefinitionResponse;
import com.tileboard.app.gameengine.dto.GameSessionStatusResponse;
import com.tileboard.app.gameengine.dto.StartGameRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Lists selectable games and controls which one (if any) is currently running on the board. */
@RestController
@RequestMapping("/api/v1/games")
public class GameController {

    private final GameRegistry gameRegistry;
    private final GameSessionManager sessionManager;

    public GameController(GameRegistry gameRegistry, GameSessionManager sessionManager) {
        this.gameRegistry = gameRegistry;
        this.sessionManager = sessionManager;
    }

    @GetMapping
    public List<GameDefinitionResponse> listGames() {
        return gameRegistry.listDefinitions().stream()
                .map(GameDefinitionResponse::from)
                .toList();
    }

    @GetMapping("/session")
    public GameSessionStatusResponse sessionStatus() {
        return new GameSessionStatusResponse(sessionManager.hasActiveGame());
    }

    @PostMapping("/{gameId}/start")
    public GameSessionStatusResponse start(@PathVariable String gameId, @RequestBody @Valid StartGameRequest request) {
        sessionManager.startGame(gameId, request.mode());
        return sessionStatus();
    }

    @PostMapping("/stop")
    public GameSessionStatusResponse stop() {
        sessionManager.stopGame();
        return sessionStatus();
    }
}
