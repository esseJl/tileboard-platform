package com.tileboard.app.dto;

import com.tileboard.engine.core.GameSession;
import com.tileboard.engine.core.GameStatus;

public record GameSessionResponse(String sessionId, String gameId, GameStatus status) {

    public static GameSessionResponse from(GameSession session) {
        return new GameSessionResponse(session.sessionId(), session.gameId(), session.status());
    }
}
