package com.tileboard.serial.hardware;

import com.tileboard.serial.board.Board;
import com.tileboard.serial.gateway.FrameListener;
import com.tileboard.serial.gateway.TileGatewayClient;
import com.tileboard.serial.gateway.handshake.DeviceAddress;
import com.tileboard.serial.gateway.handshake.SequenceValidator;
import com.tileboard.serial.gateway.handshake.SequentialIdSequenceValidator;
import com.tileboard.serial.protocol.Command;
import com.tileboard.serial.protocol.CommandType;
import com.tileboard.serial.transport.SerialPortConfig;
import com.tileboard.serial.transport.SerialPortInfo;
import com.tileboard.serial.transport.SerialPortRegistry;
import com.tileboard.serial.transport.SerialTransport;
import com.tileboard.serial.transport.jserialcomm.JSerialCommPortRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end test suite that exercises every {@link Command} against a real,
 * physically connected tile controller, plus a visual color-cycle demo.
 *
 * <h2>Why this is safe to leave in the build</h2>
 * <ul>
 *     <li>It is tagged {@code "hardware"}, and the project's default surefire
 *     configuration excludes that tag - {@code mvn test} never touches this
 *     class unless you opt in with the {@code hardware-tests} profile.</li>
 *     <li>Even if it does run, {@link EnabledIfSystemProperty} skips the whole
 *     class unless {@value HardwareTestConfig#PORT_PROPERTY} is set.</li>
 *     <li>Even if the property is set, {@link #connectToHardware()} uses
 *     {@link Assumptions} (not assertions) to skip - rather than fail - the
 *     suite if the named port isn't actually present or can't be opened, so a
 *     stale/wrong property value never shows up as a red build.</li>
 * </ul>
 *
 * <h2>Running it</h2>
 * <pre>
 * mvn test -Phardware-tests -Dtileboard.hardware.port=COM3
 * </pre>
 *
 * <p>Tests run in a fixed order ({@link Order}) because they share one live
 * connection opened once in {@link #connectToHardware()}: cheap/safe checks
 * first, the id handshake next, then command round-trips, ending with the
 * visual animation demo so a human can watch the board while it runs.
 */
@Tag("hardware")
@EnabledIfSystemProperty(named = HardwareTestConfig.PORT_PROPERTY, matches = ".+")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TileboardHardwareIT {

    private static SerialPortRegistry registry;
    private static SerialTransport transport;
    private static TileGatewayClient client;
    private static int width;
    private static int height;

    @BeforeAll
    static void connectToHardware() {
        registry = new JSerialCommPortRegistry();
        String portName = HardwareTestConfig.port();
        width = HardwareTestConfig.width();
        height = HardwareTestConfig.height();

        List<SerialPortInfo> availablePorts = registry.listPorts();
        boolean portExists = availablePorts.stream().anyMatch(p -> p.systemName().equalsIgnoreCase(portName));
        Assumptions.assumeTrue(portExists, () -> "Configured hardware port '" + portName +
                "' was not found among: " + availablePorts + " - skipping hardware test suite");

        SerialTransport openedTransport = null;
        try {
            openedTransport = registry.open(portName, SerialPortConfig.builder()
                    .baudRate(HardwareTestConfig.baudRate())
                    .build());
        } catch (RuntimeException e) {
            Assumptions.assumeTrue(false, "Could not open '" + portName + "': " + e.getMessage());
        }
        transport = openedTransport;

        client = TileGatewayClient.builder()
                .transport(transport)
                .build();
        client.start();
    }

    @AfterAll
    static void disconnectFromHardware() {
        if (client == null) {
            return;
        }
        try {
            client.send(Command.STOP, CommandType.SET);
        } catch (RuntimeException ignored) {
            // best-effort: we're tearing down anyway
        }
        client.close();
    }

    @Test
    @Order(1)
    void registryReportsTheConfiguredPort() {
        assertTrue(registry.listPorts().stream()
                .anyMatch(p -> p.systemName().equalsIgnoreCase(transport.portName())));
    }

    @Test
    @Order(2)
    void transportIsOpen() {
        assertTrue(transport.isOpen());
    }

    @Test
    @Order(3)
    void introductionCommandIsAccepted() {
        assertDoesNotThrow(() -> client.send(Command.INTRODUCTION, CommandType.SET));
    }

    /**
     * Enables the id-assignment handshake and waits for the controller to
     * report an assignment. This exercises {@code Command.ID} in both
     * directions: the library answers the controller's {@code ID}/{@code CLEAR}
     * request with the board's {@link DeviceAddress}, and this test observes
     * the controller's subsequent report.
     */
    @Test
    @Order(4)
    void idHandshakeCompletesWithAValidAssignment() throws InterruptedException {
        CountDownLatch reported = new CountDownLatch(1);
        AtomicBoolean sequenceIsValid = new AtomicBoolean(false);

        SequenceValidator observingValidator = payload -> {
            boolean valid = new SequentialIdSequenceValidator(Math.max(2, Math.min(width, height))).isValid(payload);
            sequenceIsValid.set(valid);
            reported.countDown();
            return valid;
        };
        client.enableIdHandshake(() -> DeviceAddress.forBoard(width, height), observingValidator);

        boolean reportedInTime = reported.await(HardwareTestConfig.idTimeoutSeconds(), TimeUnit.SECONDS);
        Assumptions.assumeTrue(reportedInTime,
                "Device did not report an id assignment within " + HardwareTestConfig.idTimeoutSeconds() +
                        "s - check the connection/firmware rather than treating this as a code failure");
        assertTrue(sequenceIsValid.get(), "Device reported a non-sequential / invalid id assignment");
    }

    @Test
    @Order(5)
    void startAndStopCommandsRoundTrip() {
        assertDoesNotThrow(() -> client.send(Command.START, CommandType.SET));
        sleep(HardwareTestConfig.stepDelayMillis());
        assertDoesNotThrow(() -> client.send(Command.STOP, CommandType.SET));
    }

    @Test
    @Order(6)
    void resetProgramCommandIsAccepted() {
        assertDoesNotThrow(() -> client.send(Command.RESET_PROGRAM, CommandType.SET));
        sleep(HardwareTestConfig.stepDelayMillis());
    }

    @Test
    @Order(7)
    void dataOutFillsThenClearsTheWholeBoard() {
        Board<DemoColor> filled = new Board<>(width, height, DemoColor.WHITE);
        assertDoesNotThrow(() -> client.sendBoard(Command.DATA_OUT, CommandType.SET, filled, DemoColor.codec()));
        sleep(HardwareTestConfig.stepDelayMillis());

        Board<DemoColor> cleared = new Board<>(width, height, DemoColor.OFF);
        assertDoesNotThrow(() -> client.sendBoard(Command.DATA_OUT, CommandType.SET, cleared, DemoColor.codec()));
    }

    /**
     * {@code DATA_IN} is only ever sent by the firmware in response to a
     * physical tile touch, so this step cannot assert a specific outcome. It
     * passively listens for a short window and reports what it saw; touch a
     * tile while this runs to see the count increase.
     */
    @Test
    @Order(8)
    void dataInFramesArePassivelyObserved() throws InterruptedException {
        AtomicInteger dataInFrameCount = new AtomicInteger();
        FrameListener listener = frame -> {
            if (frame.command() == Command.DATA_IN) {
                dataInFrameCount.incrementAndGet();
            }
        };
        client.addFrameListener(listener);
        try {
            System.out.println("Listening for DATA_IN frames for 3s - touch a tile now to generate one...");
            Thread.sleep(3000);
            System.out.println("Observed " + dataInFrameCount.get() + " DATA_IN frame(s).");
        } finally {
            client.removeFrameListener(listener);
        }
    }

    /**
     * Visual, human-verified demo: cycles solid colors across the whole
     * board, then sweeps a row and a column, then blinks a checkerboard
     * pattern, leaving the board off at the end. There is nothing to assert
     * here beyond "no exception was thrown while driving the hardware" -
     * a person watching the board confirms the colors are correct.
     */
    @Test
    @Order(9)
    @Tag("hardware-demo")
    void colorCycleAnimationDemo() {
        System.out.println("Watch the board: solid color cycle -> row sweep -> column sweep -> checkerboard blink.");

        for (DemoColor color : DemoColor.visiblePalette()) {
            fillAndSend(color);
            sleep(HardwareTestConfig.stepDelayMillis());
        }

        sweepRows(DemoColor.GREEN);
        sweepColumns(DemoColor.BLUE);
        blinkCheckerboard(DemoColor.RED, 3);

        fillAndSend(DemoColor.OFF);
    }

    private void fillAndSend(DemoColor color) {
        Board<DemoColor> board = new Board<>(width, height, color);
        client.sendBoard(Command.DATA_OUT, CommandType.SET, board, DemoColor.codec());
    }

    private void sweepRows(DemoColor color) {
        for (int row = 0; row < height; row++) {
            Board<DemoColor> board = new Board<>(width, height, DemoColor.OFF);
            for (int col = 0; col < width; col++) {
                board.set(row, col, color);
            }
            client.sendBoard(Command.DATA_OUT, CommandType.SET, board, DemoColor.codec());
            sleep(HardwareTestConfig.stepDelayMillis());
        }
    }

    private void sweepColumns(DemoColor color) {
        for (int col = 0; col < width; col++) {
            Board<DemoColor> board = new Board<>(width, height, DemoColor.OFF);
            for (int row = 0; row < height; row++) {
                board.set(row, col, color);
            }
            client.sendBoard(Command.DATA_OUT, CommandType.SET, board, DemoColor.codec());
            sleep(HardwareTestConfig.stepDelayMillis());
        }
    }

    private void blinkCheckerboard(DemoColor color, int cycles) {
        Board<DemoColor> even = new Board<>(width, height, DemoColor.OFF);
        Board<DemoColor> odd = new Board<>(width, height, DemoColor.OFF);
        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {
                boolean isEven = (row + col) % 2 == 0;
                even.set(row, col, isEven ? color : DemoColor.OFF);
                odd.set(row, col, isEven ? DemoColor.OFF : color);
            }
        }
        for (int i = 0; i < cycles; i++) {
            client.sendBoard(Command.DATA_OUT, CommandType.SET, even, DemoColor.codec());
            sleep(HardwareTestConfig.stepDelayMillis());
            client.sendBoard(Command.DATA_OUT, CommandType.SET, odd, DemoColor.codec());
            sleep(HardwareTestConfig.stepDelayMillis());
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
