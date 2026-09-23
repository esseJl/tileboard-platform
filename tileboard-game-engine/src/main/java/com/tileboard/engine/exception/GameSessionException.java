package com.tileboard.engine.exception;

public final class GameSessionException extends GameEngineException {

    public GameSessionException(String errorCode, Object[] args, String rawMessage) {
        super(errorCode, args, rawMessage);
    }

    public GameSessionException(String errorCode, Object[] args, String rawMessage, Throwable cause) {
        super(errorCode, args, rawMessage, cause);
    }
}
