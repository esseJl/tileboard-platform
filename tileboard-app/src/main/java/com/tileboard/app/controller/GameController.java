package com.tileboard.app.controller;

import com.tileboard.app.dto.GameDescriptorResponse;
import com.tileboard.app.dto.GameSessionResponse;
import com.tileboard.app.dto.PlayerRequest;
import com.tileboard.app.dto.StartGameRequest;
import com.tileboard.app.exception.NoActiveGameException;
import com.tileboard.engine.core.GameEngine;
import com.tileboard.engine.core.GameRegistry;
import com.tileboard.engine.model.Player;
import com.tileboard.engine.spring.GameEngineManager;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Lists registered games and controls sessions on the {@link GameEngine}.
 *
 * <p>The engine only exists while the board is actually connected - see
 * {@link GameEngineManager}. No serial port, baud rate or board size is
 * configured here or anywhere in this controller: that already happens
 * through {@link SerialPortController} and {@link DeviceController}, and
 * connecting via {@code POST /api/v1/ports/connect} is what brings the
 * engine used by this controller up.
 */
@RestController
@RequestMapping(path = "/api/v1/games",produces = MediaType.APPLICATION_JSON_VALUE)
public class GameController {

    private final GameRegistry registry;
    private final GameEngineManager engineManager;

    public GameController(GameRegistry registry, GameEngineManager engineManager) {
        this.registry = registry;
        this.engineManager = engineManager;
    }

    /** Every registered game type. Available even before the board is connected. */
    @GetMapping
    public List<GameDescriptorResponse> listGames() {
        return registry.listAll().stream()
                .map(GameDescriptorResponse::from)
                .toList();
    }

    /** Starts a new session. Requires the gateway to be connected (see {@code /api/v1/ports/connect}). */
    @PostMapping("/sessions")
    public ResponseEntity<GameSessionResponse> startGame(@RequestBody @Valid StartGameRequest request) {
        GameEngine engine = engineManager.require();
        List<Player> players = request.players().stream().map(PlayerRequest::toPlayer).toList();
        String sessionId = engine.startGame(request.gameId(), players);
        GameSessionResponse response = engine.activeSession(sessionId)
                .map(GameSessionResponse::from)
                .orElseThrow(NoActiveGameException::new);
        return ResponseEntity.ok(response);
    }

    /** All currently running sessions. */
    @GetMapping("/sessions")
    public List<GameSessionResponse> activeSessions() {
        return engineManager.current()
                .map(engine -> engine.activeSessions().stream().map(GameSessionResponse::from).toList())
                .orElseGet(List::of);
    }

    @GetMapping("/sessions/{sessionId}")
    public GameSessionResponse getSession(@PathVariable String sessionId) {
        return engineManager.current()
                .flatMap(engine -> engine.activeSession(sessionId))
                .map(GameSessionResponse::from)
                .orElseThrow(NoActiveGameException::new);
    }

    @PostMapping("/sessions/{sessionId}/stop")
    public ResponseEntity<Void> stopGame(@PathVariable String sessionId) {
        GameEngine engine = engineManager.require();
        engine.activeSession(sessionId).orElseThrow(NoActiveGameException::new);
        engine.stopGame(sessionId);
        return ResponseEntity.noContent().build();
    }
}
