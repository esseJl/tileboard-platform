package com.tileboard.serial.transport.jserialcomm;

import com.fazecast.jSerialComm.SerialPort;
import com.fazecast.jSerialComm.SerialPortDataListener;
import com.fazecast.jSerialComm.SerialPortEvent;
import com.tileboard.serial.transport.DataListener;
import com.tileboard.serial.transport.SerialTransport;
import com.tileboard.serial.exception.SerialTransportException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link SerialTransport} backed by the <a href="https://fazecast.github.io/jSerialComm/">jSerialComm</a>
 * library. This is the only class in the library that imports jSerialComm
 * types; consumers who prefer a different serial library only need to
 * implement {@link SerialTransport} themselves and never touch this class.
 *
 * <h2>Cross-platform physical-disconnect detection</h2>
 * Two signals that look authoritative are NOT reliable proof the cable is still connected once
 * this process already has the port open:
 * <ul>
 *   <li>{@link #isOpen()} - jSerialComm only clears this when {@code closePort()} is called by
 *       this process; it stays {@code true} for a handle whose USB adapter was physically
 *       unplugged but never closed.</li>
 *   <li>OS port ENUMERATION ({@code SerialPort.getCommPorts()}, used by
 *       {@link JSerialCommPortRegistry#listPorts()}) - on Windows in particular, an already-open
 *       COM port instance can remain enumerated after the cable is pulled, until the handle is
 *       closed, so a "still in the list" answer proves nothing for a port we already hold open.</li>
 * </ul>
 *
 * <p>jSerialComm has a purpose-built, cross-platform (Windows + Linux + macOS) signal for
 * exactly this instead: {@link SerialPort#LISTENING_EVENT_PORT_DISCONNECTED} (requires
 * jSerialComm >= 2.10.4; this project is on 2.11.0). Because jSerialComm only supports ONE
 * {@link SerialPortDataListener} per port, this transport registers a single internal listener
 * for the lifetime of the object - combining {@code LISTENING_EVENT_DATA_AVAILABLE} and
 * {@code LISTENING_EVENT_PORT_DISCONNECTED} in one bitmask - and only ever forwards data events
 * to the caller-supplied {@link DataListener} set via {@link #setDataListener}. That means a
 * caller clearing its data listener (passing {@code null}) can never accidentally disable
 * disconnect detection, since the two concerns are decoupled.
 *
 * <p>As defense in depth, a failed native {@code writeBytes()} call (which some drivers report
 * before the disconnect event arrives) also flips the same "physically connected" flag.
 */
public final class JSerialCommTransport implements SerialTransport {

    private static final Logger log = LoggerFactory.getLogger(JSerialCommTransport.class);

    private final SerialPort delegate;
    private final AtomicBoolean physicallyConnected = new AtomicBoolean(true);
    private volatile DataListener externalListener;

    JSerialCommTransport(SerialPort delegate) {
        this.delegate = delegate;

        delegate.addDataListener(new SerialPortDataListener() {
            @Override
            public int getListeningEvents() {
                return SerialPort.LISTENING_EVENT_DATA_AVAILABLE
                        | SerialPort.LISTENING_EVENT_PORT_DISCONNECTED;
            }

            @Override
            public void serialEvent(SerialPortEvent event) {
                switch (event.getEventType()) {
                    case SerialPort.LISTENING_EVENT_PORT_DISCONNECTED -> onPhysicalDisconnect();
                    case SerialPort.LISTENING_EVENT_DATA_AVAILABLE -> onDataAvailable();
                    default -> { /* not subscribed to anything else */ }
                }
            }
        });
    }

    private void onPhysicalDisconnect() {
        if (physicallyConnected.compareAndSet(true, false)) {
            log.error("Port {} reported LISTENING_EVENT_PORT_DISCONNECTED - the physical link is gone "
                    + "even though the handle is still open", portName());
        }
    }

    private void onDataAvailable() {
        DataListener listener = externalListener;
        if (listener == null) {
            return;
        }
        int available = delegate.bytesAvailable();
        if (available <= 0) {
            return;
        }
        byte[] data = new byte[available];
        int read = delegate.readBytes(data, available);
        if (read > 0) {
            byte[] received = read == data.length ? data : Arrays.copyOf(data, read);
            if (log.isDebugEnabled()) {
                log.debug("RX [{}] {} bytes: {}", portName(), received.length, HexFormat.of().formatHex(received));
            }
            listener.onDataReceived(received);
        }
    }

    @Override
    public String portName() {
        return delegate.getSystemPortName();
    }

    @Override
    public boolean isOpen() {
        return delegate.isOpen();
    }

    @Override
    public boolean isPhysicallyConnected() {
        return physicallyConnected.get();
    }

    @Override
    public void write(byte[] data) {
        if (!delegate.isOpen() || !physicallyConnected.get()) {
            throw new SerialTransportException("serial.write_port_not_open", new Object[]{portName()},
                    "Cannot write: port " + portName() + " is not open/connected");
        }
        if (log.isDebugEnabled()) {
            log.debug("TX [{}] {} bytes: {}", portName(), data.length, HexFormat.of().formatHex(data));
        }
        int written = delegate.writeBytes(data, data.length);
        if (written < 0) {
            // A failed native write is itself corroborating proof of a dead link, even on a
            // platform/driver where the disconnect event fires late or was missed.
            physicallyConnected.set(false);
            throw new SerialTransportException("serial.write_failed", new Object[]{portName()},
                    "Write failed on " + portName() + " (native writeBytes() returned -1)");
        }
        if (written != data.length) {
            throw new SerialTransportException("serial.short_write",
                    new Object[]{portName(), data.length, written},
                    "Short write on " + portName() + ": expected " + data.length + " bytes, wrote " + written);
        }
    }

    @Override
    public synchronized void setDataListener(DataListener listener) {
        this.externalListener = listener;
        if (listener != null) {
            log.debug("Data listener attached to {}", portName());
        } else {
            log.debug("Data listener detached from {}", portName());
        }
    }

    @Override
    public void close() {
        log.debug("Closing {}", portName());
        externalListener = null;
        delegate.removeDataListener();
        delegate.closePort();
    }
}