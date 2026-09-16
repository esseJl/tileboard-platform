package com.tileboard.serial.exception;

public class PortNotFoundException extends SerialTransportException {

    public PortNotFoundException(String portName) {
        super("Serial port not found: " + portName);
    }
}
