package com.tileboard.engine.exception;

import com.tileboard.serial.support.error.LocalizableException;

public class GameEngineException extends LocalizableException {

    public GameEngineException(String errorCode, Object[] args, String rawMessage) {
        super(errorCode, args, rawMessage);
    }

    public GameEngineException(String errorCode, Object[] args, String rawMessage, Throwable cause) {
        super(errorCode, args, rawMessage, cause);
    }
}
