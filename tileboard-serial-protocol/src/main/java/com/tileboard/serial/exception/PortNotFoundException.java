package com.tileboard.serial.exception;

public class PortNotFoundException extends SerialTransportException {

    public PortNotFoundException(String portName) {
        super("serial.port_not_found", new Object[] { portName }, "Serial port not found: " + portName);
    }
}
