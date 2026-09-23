package com.tileboard.serial.exception;

import com.tileboard.serial.support.error.LocalizableException;

/**
 * Root exception for anything that goes wrong while encoding, decoding or
 * interpreting a protocol frame.
 */
public class ProtocolException extends LocalizableException {

    public ProtocolException(String errorCode, Object[] args, String rawMessage) {
        super(errorCode, args, rawMessage);
    }

    public ProtocolException(String errorCode, Object[] args, String rawMessage, Throwable cause) {
        super(errorCode, args, rawMessage, cause);
    }
}
