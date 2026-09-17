package com.tileboard.engine.exception;

public final class GameSessionException extends GameEngineException {
    public GameSessionException(String message) { super(message); }
    public GameSessionException(String message, Throwable cause) { super(message, cause); }
}