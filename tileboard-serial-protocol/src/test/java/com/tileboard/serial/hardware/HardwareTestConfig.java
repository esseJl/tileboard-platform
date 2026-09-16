package com.tileboard.serial.hardware;

/**
 * Configuration for {@link TileboardHardwareIT}, read from JVM system
 * properties so the whole suite can be pointed at a real device without
 * touching any code:
 *
 * <pre>
 * mvn test -Phardware-tests \
 *     -Dtileboard.hardware.port=COM3 \
 *     -Dtileboard.hardware.width=8 \
 *     -Dtileboard.hardware.height=8 \
 *     -Dtileboard.hardware.baud=115200 \
 *     -Dtileboard.hardware.stepDelayMs=400
 * </pre>
 *
 * Only {@code tileboard.hardware.port} is required; everything else has a
 * sensible default. If the property is absent, the whole suite is skipped
 * (see {@code @EnabledIfSystemProperty} on the test class) instead of
 * failing, so it is safe to leave in the normal build.
 */
final class HardwareTestConfig {

    static final String PORT_PROPERTY = "tileboard.hardware.port";
    static final String WIDTH_PROPERTY = "tileboard.hardware.width";
    static final String HEIGHT_PROPERTY = "tileboard.hardware.height";
    static final String BAUD_PROPERTY = "tileboard.hardware.baud";
    static final String STEP_DELAY_PROPERTY = "tileboard.hardware.stepDelayMs";
    static final String ID_TIMEOUT_PROPERTY = "tileboard.hardware.idTimeoutSeconds";

    static final int DEFAULT_WIDTH = 3;
    static final int DEFAULT_HEIGHT = 3;
    static final int DEFAULT_BAUD_RATE = 115200;
    static final long DEFAULT_STEP_DELAY_MILLIS = 1000L;
    static final long DEFAULT_ID_TIMEOUT_SECONDS = 5L;

    private HardwareTestConfig() {
    }

    static String port() {
        return System.getProperty(PORT_PROPERTY);
    }

    static int width() {
        return Integer.getInteger(WIDTH_PROPERTY, DEFAULT_WIDTH);
    }

    static int height() {
        return Integer.getInteger(HEIGHT_PROPERTY, DEFAULT_HEIGHT);
    }

    static int baudRate() {
        return Integer.getInteger(BAUD_PROPERTY, DEFAULT_BAUD_RATE);
    }

    static long stepDelayMillis() {
        return Long.getLong(STEP_DELAY_PROPERTY, DEFAULT_STEP_DELAY_MILLIS);
    }

    static long idTimeoutSeconds() {
        return Long.getLong(ID_TIMEOUT_PROPERTY, DEFAULT_ID_TIMEOUT_SECONDS);
    }
}
