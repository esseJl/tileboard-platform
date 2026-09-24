package com.tileboard.app.service.serial;

import com.tileboard.app.config.DeviceConfiguration;
import com.tileboard.app.config.SerialMonitorProperties;
import com.tileboard.app.config.TileboardProperties;
import com.tileboard.app.exception.DeviceNotConfiguredException;
import com.tileboard.app.exception.PortsNotAssignedException;
import com.tileboard.app.exception.SerialPortOperationException;
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

import java.time.Instant;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * Default {@link SerialConnectionManager}.
 *
 * <p>WHICH ports are assigned to WHICH role is persisted through {@link SettingsService}
 * (so it survives a restart, same as device configuration); the actually-open OS handles
 * and the running {@link TileGatewayClient} are NOT persisted - a live serial connection
 * cannot be reattached after a JVM restart, so those stay purely in-memory.
 *
 * <h2>Truthful connection state</h2>
 * A remembered {@code client != null} says nothing about the cable. The reported state is
 * therefore <em>verified</em>: {@link #linkStatus()} checks that every port of the live
 * session is still enumerated by the host OS (a USB-serial adapter that is unplugged
 * disappears from that list), and {@link #releaseIfLinkLost()} lets a watchdog free a
 * dead session so the game engine stops driving a port that no longer exists.
 *
 * <h2>Threading</h2>
 * Mutations ({@link #connect()}, {@link #disconnect()}, {@link #assign}, {@link #releaseIfLinkLost()})
 * are serialized on this monitor. The read side ({@link #linkStatus()}, {@link #connectionState()})
 * is lock-free - it reads a {@code volatile} immutable {@link LinkSession} - so a health probe never
 * waits behind a slow {@code connect()}.
 */
@Service
public class DefaultSerialConnectionManager implements SerialConnectionManager {

    private static final Logger log = LoggerFactory.getLogger(DefaultSerialConnectionManager.class);

    private final SerialPortRegistry portRegistry;
    private final TileboardProperties properties;
    private final DeviceConfigurationService deviceConfigurationService;
    private final SettingsService settingsService;
    private final ApplicationEventPublisher eventPublisher;
    private final long scanCacheTtlNanos;

    /** Written only while holding this monitor; read lock-free. {@code null} = no session. */
    private volatile LinkSession session;

    /**
     * Operator intent: {@code true} = the link SHOULD be up, so {@link #reconnectIfNeeded()} may
     * bring it back after an unexpected loss. Set by {@link #armAutoReconnect()} (startup) and by
     * an explicit {@link #connect()}; cleared ONLY by an explicit {@link #disconnect()}.
     * A link that is lost on its own (cable pulled) never clears it.
     * Written under this monitor, read lock-free.
     */
    private volatile boolean autoReconnectArmed;

    /** Most recent host port enumeration (rate-limits OS scans). */
    private volatile PortScan lastScan;
    private final ReentrantLock scanLock = new ReentrantLock();

    public DefaultSerialConnectionManager(SerialPortRegistry portRegistry,
                                          TileboardProperties properties,
                                          SerialMonitorProperties monitorProperties,
                                          DeviceConfigurationService deviceConfigurationService,
                                          SettingsService settingsService,
                                          ApplicationEventPublisher eventPublisher) {
        this.portRegistry = portRegistry;
        this.properties = properties;
        this.scanCacheTtlNanos = monitorProperties.scanCacheTtl().toNanos();
        this.deviceConfigurationService = deviceConfigurationService;
        this.settingsService = settingsService;
        this.eventPublisher = eventPublisher;
    }

    // ------------------------------------------------------------------ discovery / assignment

    @Override
    public List<SerialPortSummary> listAvailablePorts() {
        // One OS enumeration for the whole list (it used to re-enumerate once per port).
        Map<String, String> descriptionByName = new LinkedHashMap<>();
        for (SerialPortInfo info : portRegistry.listPorts()) {
            descriptionByName.putIfAbsent(info.systemName(), info.description());
        }
        return descriptionByName.entrySet().stream()
                .map(e -> new SerialPortSummary(e.getKey(), e.getValue() == null ? "" : e.getValue()))
                .toList();
    }

    @Override
    public synchronized void assign(PortRole role, String portName) {
        PortAssignment updated = currentAssignment().withRole(role, portName);
        settingsService.set(SettingKeys.SERIAL_PORT_ASSIGNMENT, updated);
    }

    /** Not synchronized on purpose: a status read must not wait for a slow {@code connect()}. */
    @Override
    public PortAssignment currentAssignment() {
        return settingsService.getOrDefault(SettingKeys.SERIAL_PORT_ASSIGNMENT);
    }

    // ------------------------------------------------------------------ verified status

    @Override
    public ConnectionState connectionState() {
        return linkStatus().state();
    }

    @Override
    public SerialLinkStatus linkStatus() {
        LinkSession live = session;
        Instant now = Instant.now();
        if (live == null) {
            return SerialLinkStatus.notConnected(now);
        }

        PortScan scan = scanPorts();
        if (!scan.ok()) {
            // Cannot prove anything either way: do not claim "healthy", do not claim "lost".
            return statusOf(ConnectionState.CONNECTED, LinkCondition.UNVERIFIED, live, Set.of(), now,
                    "Host serial port list could not be read: " + scan.error());
        }

        Set<String> missing = missingPorts(live, scan);
        if (missing.isEmpty()) {
            return statusOf(ConnectionState.CONNECTED, LinkCondition.HEALTHY, live, Set.of(), now,
                    "All session ports are present on the host.");
        }
        return statusOf(ConnectionState.DISCONNECTED, LinkCondition.LINK_LOST, live, missing, now,
                "Serial port(s) " + missing + " are no longer present on the host "
                        + "(adapter unplugged or device re-enumerated).");
    }

    private static SerialLinkStatus statusOf(ConnectionState state, LinkCondition condition, LinkSession live,
                                             Set<String> missing, Instant now, String detail) {
        return new SerialLinkStatus(state, condition, live.inPort(), live.outPort(), missing,
                live.connectedSince(), now, detail);
    }

    private static Set<String> missingPorts(LinkSession live, PortScan scan) {
        Set<String> missing = new LinkedHashSet<>();
        for (String port : live.portNames()) {
            if (!scan.names().contains(port)) {
                missing.add(port);
            }
        }
        return missing;
    }

    /** Returns a recent enumeration, re-scanning at most once per {@code scan-cache-ttl}. */
    private PortScan scanPorts() {
        PortScan cached = lastScan;
        if (isFresh(cached)) {
            return cached;
        }
        if (!scanLock.tryLock()) {
            // Another thread is already enumerating (possibly slowly, e.g. Windows + Bluetooth ports).
            // Serve the previous result instead of piling up; only wait if there is none at all.
            if (cached != null) {
                return cached;
            }
            scanLock.lock();
        }
        try {
            cached = lastScan;
            if (isFresh(cached)) {
                return cached;
            }
            PortScan fresh = scanNow();
            lastScan = fresh;
            return fresh;
        } finally {
            scanLock.unlock();
        }
    }

    private boolean isFresh(PortScan scan) {
        return scan != null && System.nanoTime() - scan.takenAtNanos() < scanCacheTtlNanos;
    }

    /** Forced enumeration; never throws. */
    private PortScan scanNow() {
        try {
            Set<String> names = portRegistry.listPorts().stream()
                    .map(SerialPortInfo::systemName)
                    .collect(Collectors.toUnmodifiableSet());
            return new PortScan(names, System.nanoTime(), null);
        } catch (RuntimeException e) {
            log.warn("Serial port enumeration failed: {}", e.toString());
            return new PortScan(Set.of(), System.nanoTime(), e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ connect / disconnect

    @Override
    public synchronized void connect() {
        doConnect(true);
    }

    /**
     * @param explicit {@code true} when an operator asked for the connection: that is a statement
     *                 of intent, so auto-reconnect is (re)armed. The scheduler passes {@code false}
     *                 and therefore can never re-arm something an admin switched off.
     */
    private void doConnect(boolean explicit) {
        if (session != null) {
            // A remembered session is NOT proof of a working link: without this check a
            // stale session would make connect() a no-op forever after a cable pull.
            if (!tearDownIfLinkLost()) {
                if (explicit) {
                    autoReconnectArmed = true;
                }
                return; // genuinely connected - idempotent
            }
            log.warn("Previous serial link was lost; establishing a new one.");
        }

        PortAssignment assignment = currentAssignment();
        String outPort = assignment.outPort().orElse(null);
        String inPort = assignment.inPort().orElse(null);
        if (outPort == null) {
            throw new PortsNotAssignedException();
        }

        // Fail BEFORE any port is opened. (This used to be discovered only after the client had
        // been stored, via Optional.get(), leaving a half-connected state behind.)
        DeviceConfiguration device = deviceConfigurationService.current()
                .orElseThrow(DeviceNotConfiguredException::new);

        if (explicit) {
            // Armed once the request is valid, BEFORE opening: if the open fails (port busy,
            // adapter still enumerating) the scheduler keeps retrying it.
            autoReconnectArmed = true;
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
            enableIdHandshake(newClient, device);
            success = true;
        } finally {
            if (!success) {
                closeQuietly(openedThisAttempt.values());
            }
        }

        LinkSession live = new LinkSession(newClient, Map.copyOf(openedThisAttempt), inPort, outPort, Instant.now());
        this.session = live;
        this.lastScan = null; // a scan taken before the ports were opened must not judge this session

        try {
            eventPublisher.publishEvent(new GatewayConnectedEvent(newClient, device.width(), device.height()));
            newClient.start();
        } catch (RuntimeException e) {
            // Do not leave a "connected" session behind whose client never started.
            log.error("Gateway failed to come up on '{}'; rolling the connection back", outPort, e);
            tearDown(live, false);
            throw new SerialPortOperationException("serial.port_operation_failed", new Object[]{outPort},
                    "Failed to start the tile gateway on '" + outPort + "'", e);
        }

        log.info("Tile board gateway connected (input={}, output={})", inPort, outPort);
        try {
            newClient.send(Command.INTRODUCTION, CommandType.SET);
            log.info("sent INTRODUCTION to hardware ({}X{} board)", device.width(), device.height());
        } catch (RuntimeException e) {
            log.warn("failed to send INTRODUCTION command (gateway may have closed)");
        }
    }

    @Override
    public synchronized void disconnect() {
        // Disarm FIRST and unconditionally - also when there is no live session (e.g. the link was
        // already lost and the admin wants the scheduler to stop retrying). Same monitor as
        // reconnectIfNeeded(), so an in-flight reconnect can never slip in behind this call.
        autoReconnectArmed = false;
        LinkSession live = session;
        if (live == null) {
            return; // idempotent
        }
        tearDown(live, true);
    }

    // ------------------------------------------------------------------ auto-reconnect

    @Override
    public synchronized void armAutoReconnect() {
        autoReconnectArmed = true;
    }

    @Override
    public boolean isAutoReconnectArmed() {
        return autoReconnectArmed;
    }

    @Override
    public synchronized boolean reconnectIfNeeded() {
        if (!autoReconnectArmed) {
            return false; // never armed, or an admin disconnected on purpose
        }
        if (session != null && !tearDownIfLinkLost()) {
            return false; // link is alive - nothing to do
        }

        PortAssignment assignment = currentAssignment();
        String outPort = assignment.outPort().orElse(null);
        if (outPort == null || deviceConfigurationService.current().isEmpty()) {
            log.debug("Auto-reconnect skipped: device or output port is no longer configured");
            return false;
        }

        // Cheap pre-check: while the adapter is still unplugged do not even try to open it.
        PortScan scan = scanNow();
        lastScan = scan;
        if (scan.ok()) {
            Set<String> wanted = new LinkedHashSet<>();
            wanted.add(outPort);
            assignment.inPort().ifPresent(wanted::add);
            wanted.removeAll(scan.names());
            if (!wanted.isEmpty()) {
                log.debug("Auto-reconnect waiting: port(s) {} not present on the host yet", wanted);
                return false;
            }
        }

        try {
            doConnect(false);
            return session != null;
        } catch (RuntimeException e) {
            // Expected while the hardware is away or busy; the next tick simply tries again.
            log.warn("Auto-reconnect attempt failed: {}", e.getMessage());
            log.debug("Auto-reconnect failure details", e);
            return false;
        }
    }

    @Override
    public synchronized boolean releaseIfLinkLost() {
        return tearDownIfLinkLost();
    }

    /** Caller must hold this monitor. Forces a fresh scan; acts only on a successful scan that misses a port. */
    private boolean tearDownIfLinkLost() {
        LinkSession live = session;
        if (live == null) {
            return false;
        }
        PortScan fresh = scanNow();
        lastScan = fresh;
        if (!fresh.ok()) {
            return false; // cannot verify -> never destroy a session on a failed check
        }
        Set<String> missing = missingPorts(live, fresh);
        if (missing.isEmpty()) {
            return false;
        }
        log.error("Serial link lost: port(s) {} no longer present on the host - releasing the gateway", missing);
        tearDown(live, false);
        return true;
    }

    /** Caller must hold this monitor. */
    private void tearDown(LinkSession live, boolean sendStop) {
        session = null;   // first: from this instant nobody can observe a half-closed link as connected
        lastScan = null;
        try {
            if (sendStop) {
                try {
                    live.client().send(Command.STOP, CommandType.SET);
                    log.info("sent STOP to hardware on disconnect.");
                } catch (RuntimeException e) {
                    log.warn("Failed to send STOP on disconnect: {}", e.getMessage());
                }
            }
            try {
                live.client().close();
            } catch (RuntimeException e) {
                log.warn("Closing the gateway client failed: {}", e.getMessage());
            }
            // Idempotent safety net: never rely on the client to have released the OS handles.
            closeQuietly(live.transports().values());
        } finally {
            log.info("Tile board gateway disconnected");
            eventPublisher.publishEvent(new GatewayDisconnectedEvent());
        }
    }

    private void closeQuietly(Iterable<SerialTransport> transports) {
        for (SerialTransport transport : transports) {
            try {
                transport.close();
            } catch (RuntimeException e) {
                log.warn("Failed to close {}", transport.portName(), e);
            }
        }
    }

    private void enableIdHandshake(TileGatewayClient gatewayClient, DeviceConfiguration device) {
        int minimumSequence = properties.handshakeMinSequence() > 0
                ? properties.handshakeMinSequence()
                : Math.max(2, Math.min(device.width(), device.height()));
        log.info("Enabling id handshake for a {}x{} board (minimumSequence={})",
                device.width(), device.height(), minimumSequence);
        gatewayClient.enableIdHandshake(
                () -> DeviceAddress.forBoard(device.width(), device.height()),
                new SequentialIdSequenceValidator(minimumSequence));
    }

    private SerialTransport openPort(String portName, SerialPortConfig config) {
        try {
            return portRegistry.open(portName, config);
        } catch (RuntimeException e) {
            throw new SerialPortOperationException("serial.port_operation_failed", new Object[]{portName},
                    "Failed to open serial port '" + portName + "'", e);
        }
    }

    /** Test seam (package-private): installs a session without opening real hardware. */
    synchronized void attachSessionForTest(TileGatewayClient client, String inPort, String outPort) {
        this.session = new LinkSession(client, Map.of(), inPort, outPort, Instant.now());
        this.lastScan = null;
    }

    // ------------------------------------------------------------------ value types

    /** Immutable description of the one live gateway session. */
    private record LinkSession(TileGatewayClient client,
                               Map<PortRole, SerialTransport> transports,
                               String inPort,
                               String outPort,
                               Instant connectedSince) {

        /** Distinct system names this session depends on (IN == OUT for a shared full-duplex port). */
        Set<String> portNames() {
            Set<String> names = new LinkedHashSet<>();
            names.add(outPort);
            if (inPort != null) {
                names.add(inPort);
            }
            return names;
        }
    }

    /** Result of one host port enumeration. {@code error == null} means it succeeded. */
    private record PortScan(Set<String> names, long takenAtNanos, String error) {
        boolean ok() {
            return error == null;
        }
    }
}
