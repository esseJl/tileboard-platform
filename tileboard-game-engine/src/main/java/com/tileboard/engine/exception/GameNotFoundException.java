package com.tileboard.engine.exception;

public final class GameNotFoundException extends GameEngineException {

    public GameNotFoundException(String gameId) {
        super("game.not_found", new Object[] { gameId }, "No game registered with id: '" + gameId + "'");
    }
}
