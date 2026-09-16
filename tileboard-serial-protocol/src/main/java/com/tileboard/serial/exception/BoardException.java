package com.tileboard.serial.exception;

/**
 * Thrown for invalid board geometry or out-of-range tile access.
 */
public class BoardException extends RuntimeException {

    public BoardException(String message) {
        super(message);
    }
}
