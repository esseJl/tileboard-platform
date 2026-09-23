package com.tileboard.serial.exception;

import com.tileboard.serial.support.error.LocalizableException;

/**
 * Raised for any failure opening, writing to, or reading from a serial
 * transport.
 */
public class SerialTransportException extends LocalizableException {

    public SerialTransportException(String errorCode, Object[] args, String rawMessage) {
        super(errorCode, args, rawMessage);
    }

    public SerialTransportException(String errorCode, Object[] args, String rawMessage, Throwable cause) {
        super(errorCode, args, rawMessage, cause);
    }
}
