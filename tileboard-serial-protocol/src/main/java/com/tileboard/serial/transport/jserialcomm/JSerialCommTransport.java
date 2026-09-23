package com.tileboard.serial.transport.jserialcomm;

import com.fazecast.jSerialComm.SerialPort;
import com.fazecast.jSerialComm.SerialPortDataListener;
import com.fazecast.jSerialComm.SerialPortEvent;
import com.tileboard.serial.transport.DataListener;
import com.tileboard.serial.transport.SerialTransport;
import com.tileboard.serial.exception.SerialTransportException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HexFormat;

/**
 * {@link SerialTransport} backed by the <a href="https://fazecast.github.io/jSerialComm/">jSerialComm</a>
 * library. This is the only class in the library that imports jSerialComm
 * types; consumers who prefer a different serial library only need to
 * implement {@link SerialTransport} themselves and never touch this class.
 */
public final class JSerialCommTransport implements SerialTransport {

    private static final Logger log = LoggerFactory.getLogger(JSerialCommTransport.class);

    private final SerialPort delegate;
    private volatile SerialPortDataListener activeListener;

    JSerialCommTransport(SerialPort delegate) {
        this.delegate = delegate;
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
    public void write(byte[] data) {
        if (!delegate.isOpen()) {
            throw new SerialTransportException("serial.write_port_not_open", new Object[] { portName() },
                    "Cannot write: port " + portName() + " is not open");
        }
        if (log.isDebugEnabled()) {
            log.debug("TX [{}] {} bytes: {}", portName(), data.length, HexFormat.of().formatHex(data));
        }
        int written = delegate.writeBytes(data, data.length);
        if (written != data.length) {
            throw new SerialTransportException("serial.short_write",
                    new Object[] { portName(), data.length, written },
                    "Short write on " + portName() + ": expected " + data.length + " bytes, wrote " + written);
        }
    }

    @Override
    public synchronized void setDataListener(DataListener listener) {
        if (activeListener != null) {
            delegate.removeDataListener();
            activeListener = null;
        }
        if (listener == null) {
            return;
        }
        log.debug("Data listener attached to {}", portName());
        activeListener = new SerialPortDataListener() {
            @Override
            public int getListeningEvents() {
                return SerialPort.LISTENING_EVENT_DATA_AVAILABLE;
            }

            @Override
            public void serialEvent(SerialPortEvent event) {
                if (event.getEventType() != SerialPort.LISTENING_EVENT_DATA_AVAILABLE) {
                    return;
                }
                int available = delegate.bytesAvailable();
                if (available <= 0) {
                    return;
                }
                byte[] data = new byte[available];
                int read = delegate.readBytes(data, available);
                if (read > 0) {
                    byte[] received = read == data.length ? data : java.util.Arrays.copyOf(data, read);
                    if (log.isDebugEnabled()) {
                        log.debug("RX [{}] {} bytes: {}", portName(), received.length, HexFormat.of().formatHex(received));
                    }
                    listener.onDataReceived(received);
                }
            }
        };
        delegate.addDataListener(activeListener);
    }

    @Override
    public void close() {
        log.debug("Closing {}", portName());
        setDataListener(null);
        delegate.closePort();
    }
}
