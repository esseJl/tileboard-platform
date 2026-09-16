package com.tileboard.serial.exception;

/**
 * Thrown when an outgoing {@code Frame} cannot be encoded (e.g. payload
 * exceeds the protocol's 16-bit length field) or when bytes read from the
 * transport are irrecoverably malformed.
 */
public class InvalidFrameException extends ProtocolException {

    public InvalidFrameException(String message) {
        super(message);
    }

    public InvalidFrameException(String message, Throwable cause) {
        super(message, cause);
    }
}
