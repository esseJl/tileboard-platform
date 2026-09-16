package com.tileboard.app.serial.dto;

import com.tileboard.app.serial.ConnectionState;
import com.tileboard.app.serial.PortAssignment;

public record ConnectionStatusResponse(ConnectionState state, String inPort, String outPort) {

    public static ConnectionStatusResponse of(ConnectionState state, PortAssignment assignment) {
        return new ConnectionStatusResponse(
                state,
                assignment.inPort().orElse(null),
                assignment.outPort().orElse(null));
    }
}
