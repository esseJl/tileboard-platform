package com.tileboard.serial.exception;

/**
 * Raised for any failure opening, writing to, or reading from a serial
 * transport.
 */
public class SerialTransportException extends RuntimeException {

    public SerialTransportException(String message) {
        super(message);
    }

    public SerialTransportException(String message, Throwable cause) {
        super(message, cause);
    }
}
