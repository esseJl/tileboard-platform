package com.tileboard.app.common.exception;

import org.springframework.http.HttpStatus;

/** Wraps failures coming from the serial transport layer (port missing, open failed, ...). */
public class SerialPortOperationException extends ApiException {

    public SerialPortOperationException(String message, Throwable cause) {
        super(HttpStatus.BAD_GATEWAY, "serial_port_operation_failed", message);
        if (cause != null) {
            initCause(cause);
        }
    }
}
