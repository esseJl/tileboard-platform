package com.tileboard.app.hardware;

import com.tileboard.engine.codec.ColorTileCodec;
import com.tileboard.engine.core.BoardChannel;
import com.tileboard.engine.feature.AnimationSystem;
import com.tileboard.engine.feature.StandardAnimations;
import com.tileboard.serial.gateway.TileGatewayClient;
import com.tileboard.serial.gateway.handshake.DeviceAddress;
import com.tileboard.serial.gateway.handshake.SequentialIdSequenceValidator;
import com.tileboard.serial.protocol.Command;
import com.tileboard.serial.protocol.CommandType;
import com.tileboard.serial.transport.Parity;
import com.tileboard.serial.transport.SerialPortConfig;
import com.tileboard.serial.transport.SerialTransport;
import com.tileboard.serial.transport.jserialcomm.JSerialCommPortRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Manual, hardware-in-the-loop showcase of the board animations.
 *
 * <h2>Why this never runs by itself</h2>
 * This class opens a REAL serial port and drives REAL hardware. It must never run as a
 * side effect of {@code mvn test}, {@code mvn package}, or {@code mvn install} - those
 * commands must keep working on a machine with no tile board attached (e.g. CI).
 * <p>
 * That is enforced by {@link EnabledIfSystemProperty} on the class: unless the JVM is
 * started with {@code -Dtileboard.hardware.test=true}, JUnit reports every test in this
 * class as SKIPPED before any {@code @BeforeEach}/serial code ever runs - no port is
 * opened, nothing is instantiated. No changes to {@code pom.xml} or the default Surefire
 * configuration are required.
 *
 * <h2>Configuration</h2>
 * All configuration is via system properties (pass them with {@code -D} on the Maven
 * command line):
 * <ul>
 *   <li>{@code tileboard.hardware.test}      - REQUIRED. Must be exactly {@code true}.</li>
 *   <li>{@code tileboard.hardware.port}      - REQUIRED. Output serial port, e.g. {@code COM3}
 *       or {@code /dev/ttyUSB0}.</li>
 *   <li>{@code tileboard.hardware.inPort}    - optional. Input port; defaults to the same
 *       value as {@code tileboard.hardware.port} (single full-duplex port).</li>
 *   <li>{@code tileboard.hardware.width}     - optional, default {@code 8}. Board width in tiles.</li>
 *   <li>{@code tileboard.hardware.height}    - optional, default {@code 8}. Board height in tiles.</li>
 *   <li>{@code tileboard.hardware.baudRate}  - optional, default {@code 115200}.</li>
 *   <li>{@code tileboard.hardware.durationSeconds} - optional, default {@code 3}. How long
 *       each animation is held on screen before moving to the next one / being stopped.</li>
 *   <li>{@code tileboard.hardware.animation} - optional. When set, ALSO enables
 *       {@link #playsOnlyTheRequestedAnimation()}, which plays just that one animation key
 *       (e.g. {@code win.fireworks}) instead of the full showcase.</li>
 * </ul>
 *
 * <h2>Examples</h2>
 * Run every registered animation in order, 3s each, on COM3, 8x8 board:
 * <pre>{@code
 * mvn -pl tileboard-app test -Dtest=HardwareAnimationShowcaseTest \
 *     -Dtileboard.hardware.test=true \
 *     -Dtileboard.hardware.port=COM3 \
 *     -Dtileboard.hardware.width=8 -Dtileboard.hardware.height=8
 * }</pre>
 * Run only the "win.fireworks" animation, held for 5s:
 * <pre>{@code
 * mvn -pl tileboard-app test -Dtest=HardwareAnimationShowcaseTest#playsOnlyTheRequestedAnimation \
 *     -Dtileboard.hardware.test=true \
 *     -Dtileboard.hardware.port=COM3 \
 *     -Dtileboard.hardware.animation=win.fireworks \
 *     -Dtileboard.hardware.durationSeconds=5
 * }</pre>
 * See {@link StandardAnimations} for the full list of valid animation keys.
 */
@EnabledIfSystemProperty(named = "tileboard.hardware.test", matches = "true")
class HardwareAnimationShowcaseTest {

    private static final long DEFAULT_HOLD_SECONDS = 3;
    private static final int DEFAULT_BOARD_SIZE = 8;
    private static final int DEFAULT_BAUD_RATE = 115_200;

    private TileGatewayClient gateway;
    private AnimationSystem animationSystem;

    @BeforeEach
    void connectToRealHardware() {
        String outPort = requiredProperty("tileboard.hardware.port",
                "Set -Dtileboard.hardware.port=<COM_PORT> to run hardware tests.");
        String inPort = System.getProperty("tileboard.hardware.inPort", outPort);
        int width = intProperty("tileboard.hardware.width", DEFAULT_BOARD_SIZE);
        int height = intProperty("tileboard.hardware.height", DEFAULT_BOARD_SIZE);
        int baudRate = intProperty("tileboard.hardware.baudRate", DEFAULT_BAUD_RATE);

        SerialPortConfig config = SerialPortConfig.builder()
                .baudRate(baudRate)
                .dataBits(8)
                .stopBits(1)
                .parity(Parity.NONE)
                .readTimeoutMillis(50)
                .writeTimeoutMillis(50)
                .build();

        JSerialCommPortRegistry portRegistry = new JSerialCommPortRegistry();
        TileGatewayClient.Builder builder = TileGatewayClient.builder();
        if (inPort.equals(outPort)) {
            SerialTransport shared = portRegistry.open(outPort, config);
            builder.transport(shared);
        } else {
            builder.outputTransport(portRegistry.open(outPort, config));
            builder.inputTransport(portRegistry.open(inPort, config));
        }
        gateway = builder.build();

        int minimumSequence = Math.max(2, Math.min(width, height));
        gateway.enableIdHandshake(() -> DeviceAddress.forBoard(width, height),
                new SequentialIdSequenceValidator(minimumSequence));
        gateway.start();
        gateway.send(Command.INTRODUCTION, CommandType.SET);

        BoardChannel boardChannel = new BoardChannel(width, height, gateway, ColorTileCodec.instance());
        animationSystem = new AnimationSystem(width, height, boardChannel::publish);
    }

    @AfterEach
    void disconnectFromHardware() {
        if (animationSystem != null) {
            animationSystem.shutdown();
        }
        if (gateway != null) {
            try {
                gateway.send(Command.STOP, CommandType.SET);
            } catch (RuntimeException ignored) {
                // gateway may already be unreachable during teardown - never fail the test on this
            }
            gateway.close();
        }
    }

    @Test
    @DisplayName("Plays every registered animation, in order, holding each on real hardware")
    void playsEveryRegisteredAnimationInOrder() throws InterruptedException {
        long holdMillis = holdMillis();
        List<String> animationKeys = discoverAllAnimationKeys();
        Assertions.assertFalse(animationKeys.isEmpty(),
                "No animation keys were found on StandardAnimations - nothing to showcase.");

        for (String key : animationKeys) {
            System.out.printf("[hardware-test] playing '%s' for %ds ...%n", key, holdMillis / 1000);
            animationSystem.play(key);
            Thread.sleep(holdMillis);
        }
        animationSystem.cancelCurrent();
    }

    @Test
    @DisplayName("Plays only the single animation named by -Dtileboard.hardware.animation")
    @EnabledIfSystemProperty(named = "tileboard.hardware.animation", matches = ".+")
    void playsOnlyTheRequestedAnimation() {
        String key = System.getProperty("tileboard.hardware.animation");
        long holdMillis = holdMillis();

        System.out.printf("[hardware-test] playing only '%s' for %ds ...%n", key, holdMillis / 1000);
        CompletableFuture<Void> future = animationSystem.play(key);
        try {
            // Non-looping animations (win/lose/countdown) may finish before the hold window;
            // looping ones (standby.*) run until the timeout below, exactly like the intent.
            future.get(holdMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException expected) {
            // still playing after the hold window - that is fine, we stop it explicitly next.
        } catch (ExecutionException | InterruptedException e) {
            Assertions.fail("Animation '" + key + "' failed while running", e);
        } finally {
            animationSystem.cancelCurrent();
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Reflects over StandardAnimations' public static final String constants so newly added
     *  animations are picked up automatically, without this test needing to be edited. */
    private static List<String> discoverAllAnimationKeys() {
        List<String> keys = new ArrayList<>();
        for (Field field : StandardAnimations.class.getDeclaredFields()) {
            int modifiers = field.getModifiers();
            boolean isAnimationKeyConstant = Modifier.isPublic(modifiers)
                    && Modifier.isStatic(modifiers)
                    && Modifier.isFinal(modifiers)
                    && field.getType() == String.class;
            if (isAnimationKeyConstant) {
                try {
                    keys.add((String) field.get(null));
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException("Could not read animation key constant: " + field.getName(), e);
                }
            }
        }
        return keys;
    }

    private static long holdMillis() {
        return longProperty("tileboard.hardware.durationSeconds", DEFAULT_HOLD_SECONDS) * 1000;
    }

    private static String requiredProperty(String name, String message) {
        String value = System.getProperty(name);
        Assumptions.assumeTrue(value != null && !value.isBlank(), message);
        return value;
    }

    private static int intProperty(String name, int defaultValue) {
        String value = System.getProperty(name);
        return value == null || value.isBlank() ? defaultValue : Integer.parseInt(value);
    }

    private static long longProperty(String name, long defaultValue) {
        String value = System.getProperty(name);
        return value == null || value.isBlank() ? defaultValue : Long.parseLong(value);
    }
}
