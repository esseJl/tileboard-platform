package com.tileboard.app.common.exception;

import org.springframework.http.HttpStatus;

/** Thrown when a connection is attempted before the required serial ports were assigned. */
public class PortsNotAssignedException extends ApiException {

    public PortsNotAssignedException() {
        super(HttpStatus.CONFLICT, "ports_not_assigned",
                "At least the output port must be assigned before connecting.");
    }
}
