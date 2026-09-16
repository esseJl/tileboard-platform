package com.tileboard.serial.transport;

/**
 * Serial line parity setting. Generic to serial communication, not specific
 * to any device or vendor library.
 */
public enum Parity {
    NONE,
    ODD,
    EVEN,
    MARK,
    SPACE
}
