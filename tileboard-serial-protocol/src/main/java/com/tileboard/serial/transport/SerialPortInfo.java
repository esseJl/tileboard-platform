package com.tileboard.serial.transport;

/**
 * Describes a serial port available on the host, as reported by a
 * {@link SerialPortRegistry}. The system name (e.g. {@code "COM3"} or
 * {@code "/dev/ttyUSB0"}) is a plain string discovered at runtime - the
 * library never hard-codes a fixed set of port names.
 */
public record SerialPortInfo(String systemName, String description) {
}
