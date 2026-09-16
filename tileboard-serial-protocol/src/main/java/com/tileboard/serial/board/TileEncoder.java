package com.tileboard.serial.board;

/**
 * Converts a single application-level tile value to the raw byte the device
 * expects. Kept separate from {@link TileDecoder} so a {@link Board} can be
 * declared over any type the application already has (an enum of colors, an
 * int intensity, a custom record, ...) without the library dictating what a
 * "tile" is.
 *
 * @param <T> the application's tile type, e.g. an enum of colors
 */
@FunctionalInterface
public interface TileEncoder<T> {

    byte encode(T tile);
}
