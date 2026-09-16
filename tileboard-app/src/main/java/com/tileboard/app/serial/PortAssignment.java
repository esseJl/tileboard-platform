package com.tileboard.app.serial;

import java.util.Optional;

/** Which system port name is currently assigned to each {@link PortRole}. */
public record PortAssignment(Optional<String> inPort, Optional<String> outPort) {

    public static PortAssignment empty() {
        return new PortAssignment(Optional.empty(), Optional.empty());
    }

    public boolean isOutAssigned() {
        return outPort.isPresent();
    }
}
