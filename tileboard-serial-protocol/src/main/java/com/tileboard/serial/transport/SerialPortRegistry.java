package com.tileboard.serial.transport;

import com.tileboard.serial.exception.PortNotFoundException;
import com.tileboard.serial.exception.SerialTransportException;

import java.util.List;

/**
 * Discovers serial ports available on the host and opens them as
 * {@link SerialTransport} instances. Kept separate from {@link SerialTransport}
 * so port discovery (which needs to enumerate the whole system) and an
 * individual open connection remain independently mockable/testable.
 */
public interface SerialPortRegistry {

    /** Lists every serial port currently visible to the host OS. */
    List<SerialPortInfo> listPorts();

    /**
     * Opens {@code portName} with the given configuration.
     *
     * @throws PortNotFoundException    if no port with that system name exists
     * @throws SerialTransportException if the port exists but could not be opened
     */
    SerialTransport open(String portName, SerialPortConfig config);
}
