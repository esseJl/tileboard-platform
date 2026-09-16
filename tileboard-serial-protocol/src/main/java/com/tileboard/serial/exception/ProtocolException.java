package com.tileboard.serial.exception;

/**
 * Root exception for anything that goes wrong while encoding, decoding or
 * interpreting a protocol frame.
 */
public class ProtocolException extends RuntimeException {

    public ProtocolException(String message) {
        super(message);
    }

    public ProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
