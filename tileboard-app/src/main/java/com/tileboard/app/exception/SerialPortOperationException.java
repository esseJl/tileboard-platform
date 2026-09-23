package com.tileboard.app.exception;

import org.springframework.http.HttpStatus;

/** Wraps failures coming from the serial transport layer (port missing, open failed, ...). */
public class SerialPortOperationException extends ApiException {

    public SerialPortOperationException(String errorCode, Object[] args, String rawMessage, Throwable cause) {
        super(HttpStatus.BAD_GATEWAY, errorCode, args, rawMessage, cause);
    }
}
