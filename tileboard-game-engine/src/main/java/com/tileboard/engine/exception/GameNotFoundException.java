package com.tileboard.engine.exception;

public final class GameNotFoundException extends GameEngineException {
    public GameNotFoundException(String gameId) {
        super("No game registered with id: '" + gameId + "'");
    }
}