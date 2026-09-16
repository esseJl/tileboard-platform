package com.tileboard.serial.transport;

import com.tileboard.serial.exception.SerialTransportException;

/**
 * An abstraction over a single, already-open serial connection.
 *
 * <p>The library ships one implementation backed by jSerialComm
 * ({@code com.tileboard.serial.transport.jserialcomm.JSerialCommTransport}),
 * but any hardware or test double can implement this interface directly -
 * nothing above this layer (framing, board codec, gateway, handshake) knows
 * or cares which serial library is underneath.
 */
public interface SerialTransport extends AutoCloseable {

    /** The system name this transport is bound to, e.g. {@code "COM3"} or {@code "/dev/ttyUSB0"}. */
    String portName();

    boolean isOpen();

    /**
     * Writes {@code data} to the wire. Implementations should block until the
     * bytes are handed to the OS driver (or the configured write timeout
     * elapses).
     *
     * @throws SerialTransportException if the port is closed or the write fails
     */
    void write(byte[] data);

    /**
     * Registers the single listener notified when new bytes arrive. Passing
     * {@code null} removes any previously registered listener. Only one
     * listener is supported per transport by design - fan-out, if needed, is
     * the caller's responsibility (e.g. a {@code FrameDecoder} dispatching to
     * multiple application listeners).
     */
    void setDataListener(DataListener listener);

    @Override
    void close();
}
