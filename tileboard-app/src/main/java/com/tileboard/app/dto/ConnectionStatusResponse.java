package com.tileboard.app.dto;

import com.tileboard.app.service.serial.ConnectionState;
import com.tileboard.app.service.serial.PortAssignment;

public record ConnectionStatusResponse(ConnectionState state, String inPort, String outPort) {

    public static ConnectionStatusResponse of(ConnectionState state, PortAssignment assignment) {
        return new ConnectionStatusResponse(
                state,
                assignment.inPort().orElse(null),
                assignment.outPort().orElse(null));
    }
}
