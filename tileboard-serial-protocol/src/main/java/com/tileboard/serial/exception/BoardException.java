package com.tileboard.serial.exception;

import com.tileboard.serial.support.error.LocalizableException;

/**
 * Thrown for invalid board geometry or out-of-range tile access.
 */
public class BoardException extends LocalizableException {

    public BoardException(String errorCode, Object[] args, String rawMessage) {
        super(errorCode, args, rawMessage);
    }
}
