package com.tileboard.app.service.serial;

import com.tileboard.app.config.DeviceConfiguration;
import com.tileboard.app.exception.PortsNotAssignedException;
import com.tileboard.app.exception.SerialPortOperationException;
import com.tileboard.app.config.TileboardProperties;
import com.tileboard.app.service.device.DeviceConfigurationService;
import com.tileboard.app.settings.SettingKeys;
import com.tileboard.app.settings.SettingsService;
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

/**
 * Default {@link SerialConnectionManager}.
 *
 * <p>WHICH ports are assigned to WHICH role is persisted through {@link SettingsService}
 * (so it survives a restart, same as device configuration); the actually-open OS handles
 * and the running {@link TileGatewayClient} are NOT persisted - a live serial connection
 * cannot be reattached after a JVM restart, so those stay purely in-memory.
 */
@Service
public class DefaultSerialConnectionManager implements SerialConnectionManager {

    private static final Logger log = LoggerFactory.getLogger(DefaultSerialConnectionManager.class);

    private final SerialPortRegistry portRegistry;
    private final TileboardProperties properties;
    private final DeviceConfigurationService deviceConfigurationService;
    private final SettingsService settingsService;
    private final ApplicationEventPublisher eventPublisher;

    private final Map<PortRole, SerialTransport> openTransports = new EnumMap<>(PortRole.class);
    private TileGatewayClient client;

    public DefaultSerialConnectionManager(SerialPortRegistry portRegistry,
                                          TileboardProperties properties,
                                          DeviceConfigurationService deviceConfigurationService,
                                          SettingsService settingsService,
                                          ApplicationEventPublisher eventPublisher) {
        this.portRegistry = portRegistry;
        this.properties = properties;
        this.deviceConfigurationService = deviceConfigurationService;
        this.settingsService = settingsService;
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
        PortAssignment updated = currentAssignment().withRole(role, portName);
        settingsService.set(SettingKeys.SERIAL_PORT_ASSIGNMENT, updated);
    }

    @Override
    public synchronized PortAssignment currentAssignment() {
        return settingsService.getOrDefault(SettingKeys.SERIAL_PORT_ASSIGNMENT);
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

        if (deviceConfigurationService.current().isEmpty()) {
            log.info("Device not Configured - can not connect.");
        }

        PortAssignment assignment = currentAssignment();
        String outPort = assignment.outPort().orElse(null);
        String inPort = assignment.inPort().orElse(null);
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
                    log.warn("No IN port assigned - connecting OUTPUT ONLY. The board's touches and "
                            + "id handshake will never be received. If your controller uses a single "
                            + "full-duplex port, assign the SAME port name to both IN and OUT.");
                }
            }

            newClient = builder.build();
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
            log.info("sent INTRODUCTION to hardware ({}X{} board)", device.width(), device.height());
        } catch (RuntimeException e) {
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
            throw new SerialPortOperationException("serial.port_operation_failed", new Object[]{portName},
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
                client.send(Command.STOP, CommandType.SET);
                log.info("sent STOP to hardware on disconnected.");
            } catch (RuntimeException e) {
                log.warn("Failed to send STOP on disconnected: {}", e.getMessage());
            }
            client.close();
        } finally {
            client = null;
            openTransports.clear();
            log.info("Tile board gateway disconnected");
            eventPublisher.publishEvent(new GatewayDisconnectedEvent());
        }
    }
}