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

    /**
     * {@code true} until {@link #close()} is called on THIS object. This is NOT proof the
     * physical device is still attached - most drivers/OSes keep a handle "open" from the
     * process's point of view until it is explicitly closed, even after the cable has been
     * pulled. Use {@link #isPhysicallyConnected()} to answer "is the hardware still there?".
     */
    boolean isOpen();

    /**
     * Best-effort, OS/driver-level answer to "is the physical device still there right now?" -
     * independent of {@link #isOpen()} (which only reflects whether {@code close()} has been
     * called) and independent of any higher-level protocol traffic.
     *
     * <p>Implementations MUST default to {@code true} ("no proof of absence") whenever the
     * underlying platform/driver cannot reliably report removal, so a flaky or unsupported
     * signal on some platform can never cause a healthy link to be misreported as lost. A
     * {@code false} return must only ever be the result of a positive, driver-reported signal
     * (e.g. jSerialComm's {@code LISTENING_EVENT_PORT_DISCONNECTED}) or a genuine native I/O
     * failure - never of a plain OS port-enumeration miss, which callers already check
     * separately and which is known to lag or lie for a port this same process still holds
     * open (Windows in particular can keep an open COM port enumerated after the cable is
     * pulled, until the handle is closed).
     */
    boolean isPhysicallyConnected();

    /**
     * Writes {@code data} to the wire. Implementations should block until the
     * bytes are handed to the OS driver (or the configured write timeout
     * elapses).
     *
     * @throws SerialTransportException if the port is closed, not physically connected,
     *         or the write fails
     */
    void write(byte[] data);

    /**
     * Registers the single listener notified when new bytes arrive. Passing
     * {@code null} removes any previously registered listener. Only one
     * listener is supported per transport by design - fan-out, if needed, is
     * the caller's responsibility (e.g. a {@code FrameDecoder} dispatching to
     * multiple application listeners).
     *
     * <p>This only affects data callbacks. Physical-disconnect detection (see
     * {@link #isPhysicallyConnected()}) is managed internally by the transport and is
     * NEVER disabled by passing {@code null} here.
     */
    void setDataListener(DataListener listener);

    @Override
    void close();
}
