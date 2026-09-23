package com.tileboard.app.service.serial;

import com.tileboard.app.config.DeviceConfiguration;
import com.tileboard.app.exception.DeviceNotConfiguredException;
import com.tileboard.app.exception.PortsNotAssignedException;
import com.tileboard.app.exception.SerialPortOperationException;
import com.tileboard.app.config.TileboardProperties;
import com.tileboard.app.service.device.DeviceConfigurationService;
import com.tileboard.engine.spring.GatewayConnectedEvent;
import com.tileboard.engine.spring.GatewayDisconnectedEvent;
import com.tileboard.serial.gateway.TileGatewayClient;
import com.tileboard.serial.gateway.handshake.DeviceAddress;
import com.tileboard.serial.gateway.handshake.SequentialIdSequenceValidator;
import com.tileboard.serial.protocol.Command;
import com.tileboard.serial.protocol.CommandType;
import com.tileboard.serial.transport.Parity;
import com.tileboard.serial.transport.SerialPortConfig;
import com.tileboard.serial.transport.SerialPortInfo;
import com.tileboard.serial.transport.SerialPortRegistry;
import com.tileboard.serial.transport.SerialTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Default {@link SerialConnectionManager}.
 *
 * <p>Supports two topologies transparently: a single full-duplex port
 * assigned to both {@link PortRole#IN} and {@link PortRole#OUT} (opened
 * once), or two independent half-duplex adapters (opened separately) - the
 * caller only ever assigns roles to port names, never has to think about
 * which topology results.
 *
 * <p>Not meant to be used concurrently from many threads doing
 * connect/disconnect at once (an operator-driven admin action), so the
 * mutating methods are simply {@code synchronized}.
 */
@Service
public class DefaultSerialConnectionManager implements SerialConnectionManager {

    private static final Logger log = LoggerFactory.getLogger(DefaultSerialConnectionManager.class);

    private final SerialPortRegistry portRegistry;
    private final TileboardProperties properties;
    private final DeviceConfigurationService deviceConfigurationService;
    private final ApplicationEventPublisher eventPublisher;

    private final Map<PortRole, String> assignedPorts = new EnumMap<>(PortRole.class);
    private final Map<PortRole, SerialTransport> openTransports = new EnumMap<>(PortRole.class);
    private TileGatewayClient client;

    public DefaultSerialConnectionManager(SerialPortRegistry portRegistry,
                                          TileboardProperties properties,
                                          DeviceConfigurationService deviceConfigurationService,
                                          ApplicationEventPublisher eventPublisher) {
        this.portRegistry = portRegistry;
        this.properties = properties;
        this.deviceConfigurationService = deviceConfigurationService;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public List<SerialPortSummary> listAvailablePorts() {
        return portRegistry.listPorts().stream()
                .map(SerialPortInfo::systemName)
                .distinct()
                .map(name -> new SerialPortSummary(name, describe(name)))
                .toList();
    }

    private String describe(String systemName) {
        return portRegistry.listPorts().stream()
                .filter(info -> info.systemName().equals(systemName))
                .map(SerialPortInfo::description)
                .findFirst()
                .orElse("");
    }

    @Override
    public synchronized void assign(PortRole role, String portName) {
        assignedPorts.put(role, portName);
    }

    @Override
    public synchronized PortAssignment currentAssignment() {
        return new PortAssignment(
                Optional.ofNullable(assignedPorts.get(PortRole.IN)),
                Optional.ofNullable(assignedPorts.get(PortRole.OUT)));
    }

    @Override
    public synchronized ConnectionState connectionState() {
        return client != null ? ConnectionState.CONNECTED : ConnectionState.DISCONNECTED;
    }

    @Override
    public synchronized void connect() {
        if (client != null) {
            return;
        }

        if (deviceConfigurationService.current().isEmpty()){
            log.info("Device not Configured - can not connect.");
        }

        String outPort = assignedPorts.get(PortRole.OUT);
        String inPort = assignedPorts.get(PortRole.IN);
        if (outPort == null) {
            throw new PortsNotAssignedException();
        }

        SerialPortConfig config = SerialPortConfig.builder()
                .baudRate(properties.baudRate())
                .dataBits(properties.dataBits())
                .stopBits(properties.stopBits())
                .parity(Parity.NONE)
                .readTimeoutMillis(properties.readTimeoutMillis())
                .writeTimeoutMillis(properties.writeTimeoutMillis())
                .build();

        // Opened transports are tracked locally first, and only merged into
        // the real openTransports/client state once every step below
        // succeeds. If opening the second port (or anything else in this
        // block) throws, the finally block closes whatever WAS opened in
        // this attempt instead of leaking it - otherwise a failed connect
        // would hold the OS port handle open forever with nothing left
        // referencing it, and the next connect() attempt would fail again
        // trying to reopen the same physical port.
        Map<PortRole, SerialTransport> openedThisAttempt = new EnumMap<>(PortRole.class);
        TileGatewayClient newClient;
        boolean success = false;
        try {
            TileGatewayClient.Builder builder = TileGatewayClient.builder();
            if (inPort != null && inPort.equals(outPort)) {
                SerialTransport shared = openPort(outPort, config);
                openedThisAttempt.put(PortRole.OUT, shared);
                builder.transport(shared);
            } else {
                SerialTransport outTransport = openPort(outPort, config);
                openedThisAttempt.put(PortRole.OUT, outTransport);
                builder.outputTransport(outTransport);

                if (inPort != null) {
                    SerialTransport inTransport = openPort(inPort, config);
                    openedThisAttempt.put(PortRole.IN, inTransport);
                    builder.inputTransport(inTransport);
                } else {
                    // Silently proceeding here would build a send-only client:
                    // TileGatewayClient.start() only calls setDataListener() when
                    // an inputTransport is present, so with none assigned no
                    // touch/handshake byte would ever be read - with no error
                    // anywhere. Loud and explicit beats silently half-working.
                    log.warn("No IN port assigned - connecting OUTPUT ONLY. The board's touches and "
                            + "id handshake will never be received. If your controller uses a single "
                            + "full-duplex port, assign the SAME port name to both IN and OUT.");
                }
            }

            newClient = builder.build();
            // Register every FrameListener (currently just the handshake
            // coordinator) BEFORE start() opens the input pipe. Calling
            // start() first would let the board's very first frames (e.g.
            // its initial ID/CLEAR handshake request) arrive before anything
            // is listening for them, silently dropping them since
            // TileGatewayClient.dispatch() only notifies listeners that were
            // registered by the time a frame is decoded.
            enableHandshakeIfDeviceKnown(newClient);
            success = true;
        } finally {
            if (!success) {
                closeQuietly(openedThisAttempt.values());
            }
        }

        openTransports.putAll(openedThisAttempt);
        this.client = newClient;

            eventPublisher.publishEvent(
                    new GatewayConnectedEvent(newClient,
                            deviceConfigurationService.current().get().width(),
                            deviceConfigurationService.current().get().height()));
            newClient.start();
        log.info("Tile board gateway connected (input={}, output={})", inPort, outPort);
        try {
            DeviceConfiguration device = deviceConfigurationService.current().get();
            newClient.send(Command.INTRODUCTION, CommandType.SET);
            log.info("sent INTRODUCTION to hardware ({}X{} board)",device.width(),device.height());
        }catch (RuntimeException e){
            log.warn("failed to send INTRODUCTION command (gateway may have closed)");
        }

    }

    private void closeQuietly(Iterable<SerialTransport> transports) {
        for (SerialTransport transport : transports) {
            try {
                transport.close();
            } catch (RuntimeException e) {
                log.warn("Failed to close {} while rolling back a failed connect attempt", transport.portName(), e);
            }
        }
    }

    private void enableHandshakeIfDeviceKnown(TileGatewayClient gatewayClient) {
        deviceConfigurationService.current().ifPresentOrElse(
                device -> {
                    int minimumSequence = properties.handshakeMinSequence() > 0
                            ? properties.handshakeMinSequence()
                            : Math.max(2, Math.min(device.width(), device.height()));
                    log.info("Enabling id handshake for a {}x{} board (minimumSequence={})",
                            device.width(), device.height(), minimumSequence);
                    gatewayClient.enableIdHandshake(
                            () -> DeviceAddress.forBoard(device.width(), device.height()),
                            new SequentialIdSequenceValidator(minimumSequence));
                },
                () -> log.warn("Connecting without a device configuration - the id handshake will not "
                        + "be enabled until the device is configured and the gateway is reconnected."));
    }

    private SerialTransport openPort(String portName, SerialPortConfig config) {
        try {
            return portRegistry.open(portName, config);
        } catch (RuntimeException e) {
            throw new SerialPortOperationException("serial.port_operation_failed", new Object[] { portName },
                    "Failed to open serial port '" + portName + "'", e);
        }
    }

    @Override
    public synchronized void disconnect() {
        if (client == null) {
            return;
        }
        try {
            try {
                client.send(Command.STOP,CommandType.SET);
                log.info("sent STOP to hardware on disconnected.");
            }catch (RuntimeException e){
                log.warn("Failed to sned STOP on disconnected: {}",e.getMessage());
            }
            client.close();
        } finally {
            // Always drop our reference and tell the rest of the app the
            // gateway is gone, even if close() itself threw - staying
            // "connected" after a failed close would leave GameSessionManager
            // holding a client that can no longer be used.
            client = null;
            openTransports.clear();
            log.info("Tile board gateway disconnected");
            eventPublisher.publishEvent(new GatewayDisconnectedEvent());
        }
    }
}
