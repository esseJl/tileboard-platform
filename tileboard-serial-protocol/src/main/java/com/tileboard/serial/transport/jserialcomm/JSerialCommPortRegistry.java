package com.tileboard.serial.transport.jserialcomm;

import com.fazecast.jSerialComm.SerialPort;
import com.tileboard.serial.transport.*;
import com.tileboard.serial.exception.PortNotFoundException;
import com.tileboard.serial.exception.SerialTransportException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;

/**
 * Default, ready-to-use {@link SerialPortRegistry} for desktop/server JVMs,
 * delegating to jSerialComm. Requires the (optional) {@code com.fazecast:jSerialComm}
 * dependency to be present on the consumer's classpath.
 */
public final class JSerialCommPortRegistry implements SerialPortRegistry {

    private static final Logger log = LoggerFactory.getLogger(JSerialCommPortRegistry.class);

    @Override
    public List<SerialPortInfo> listPorts() {
        return Arrays.stream(SerialPort.getCommPorts())
                .map(p -> new SerialPortInfo(p.getSystemPortName(), p.getDescriptivePortName()))
                .toList();
    }

    @Override
    public SerialTransport open(String portName, SerialPortConfig config) {
        boolean exists = Arrays.stream(SerialPort.getCommPorts())
                .anyMatch(p -> p.getSystemPortName().equalsIgnoreCase(portName));
        if (!exists) {
            throw new PortNotFoundException(portName);
        }

        SerialPort port = SerialPort.getCommPort(portName);
        port.setComPortParameters(
                config.baudRate(),
                config.dataBits(),
                toJSerialCommStopBits(config.stopBits()),
                toJSerialCommParity(config.parity()));
        port.setComPortTimeouts(
                SerialPort.TIMEOUT_READ_SEMI_BLOCKING | SerialPort.TIMEOUT_WRITE_BLOCKING,
                config.readTimeoutMillis(),
                config.writeTimeoutMillis());
        port.setFlowControl(toJSerialCommFlowControl(config.flowControl()));

        if (!port.openPort()) {
            throw new SerialTransportException("Failed to open serial port: " + portName);
        }
        boolean clearDTR = port.clearDTR();
        boolean clearRTS = port.clearRTS();
        if (!clearDTR || !clearRTS){
            log.warn("Could not assert control lines on {} (DTR={}, RTS={}); the device may not receive data even though write report success",portName,clearDTR,clearRTS);
        }
        return new JSerialCommTransport(port);
    }

    private int toJSerialCommFlowControl(FlowControl flowControl) {
        return switch (flowControl){
            case NONE -> SerialPort.FLOW_CONTROL_DISABLED;
            case RTS_CTS -> SerialPort.FLOW_CONTROL_RTS_ENABLED | SerialPort.FLOW_CONTROL_CTS_ENABLED;
            case XON_XOFF -> SerialPort.FLOW_CONTROL_XONXOFF_IN_ENABLED | SerialPort.FLOW_CONTROL_XONXOFF_OUT_ENABLED;
        };
    }

    private static int toJSerialCommStopBits(int stopBits) {
        return switch (stopBits) {
            case 2 -> SerialPort.TWO_STOP_BITS;
            case 15 -> SerialPort.ONE_POINT_FIVE_STOP_BITS;
            default -> SerialPort.ONE_STOP_BIT;
        };
    }

    private static int toJSerialCommParity(Parity parity) {
        return switch (parity) {
            case ODD -> SerialPort.ODD_PARITY;
            case EVEN -> SerialPort.EVEN_PARITY;
            case MARK -> SerialPort.MARK_PARITY;
            case SPACE -> SerialPort.SPACE_PARITY;
            case NONE -> SerialPort.NO_PARITY;
        };
    }
}
