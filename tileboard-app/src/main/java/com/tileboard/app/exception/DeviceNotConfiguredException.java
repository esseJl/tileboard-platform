package com.tileboard.app.exception;

import org.springframework.http.HttpStatus;

/** Thrown when an operation needs the board's width/height but none has been set yet. */
public class DeviceNotConfiguredException extends ApiException {

    public DeviceNotConfiguredException() {
        super(HttpStatus.CONFLICT, "device_not_configured",
                "No device (board width/height) has been configured yet.");
    }
}
