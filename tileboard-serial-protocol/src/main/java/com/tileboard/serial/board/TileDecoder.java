package com.tileboard.serial.board;

/**
 * Converts a raw byte received from the device back into an application-level
 * tile value. See {@link TileEncoder} for the reverse direction and rationale.
 *
 * @param <T> the application's tile type, e.g. an enum of colors
 */
@FunctionalInterface
public interface TileDecoder<T> {

    T decode(byte wireValue);
}
