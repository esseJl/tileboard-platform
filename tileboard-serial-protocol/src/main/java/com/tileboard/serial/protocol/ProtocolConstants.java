package com.tileboard.serial.protocol;

/**
 * Wire-level constants shared by every frame exchanged with the tile
 * controller. These are protocol constants (fixed by the hardware firmware),
 * not application configuration, so they live in one place instead of being
 * scattered as magic numbers through the codebase.
 *
 * <p>Frame layout:
 * <pre>
 *   byte 0      : START_BYTE            (0xFC)
 *   byte 1      : SEPARATOR             (':')
 *   byte 2      : command code          (see {@link Command})
 *   byte 3      : command type code     (see {@link CommandType})
 *   byte 4      : payload length, high byte (big endian, unsigned 16 bit)
 *   byte 5      : payload length, low byte
 *   byte 6..n-2 : payload (0..N bytes)
 *   byte n-1    : END_BYTE              ('#')
 * </pre>
 */
public final class ProtocolConstants {

    /** First byte of every frame. */
    public static final byte START_BYTE = (byte) 0xFC;

    /** Second byte of every frame. */
    public static final byte SEPARATOR_BYTE = ':';

    /** Last byte of every frame. */
    public static final byte END_BYTE = '#';

    /** Number of non-payload bytes in a frame: start, sep, cmd, type, len(2), end. */
    public static final int FRAME_OVERHEAD_BYTES = 7;

    /** Maximum payload length representable in the 16-bit length field. */
    public static final int MAX_PAYLOAD_LENGTH = 0xFFFF;

    private ProtocolConstants() {
    }
}
