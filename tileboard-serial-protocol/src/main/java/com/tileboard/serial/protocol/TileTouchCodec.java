package com.tileboard.serial.protocol;

import com.tileboard.serial.board.TileCodec;

/**
 * Wire codec for tile-touch state.
 *
 * <p>Per the tileboard serial protocol, a {@code DATA_IN} frame's payload is
 * a row-major flat array with one byte per tile: {@code 0x00} means the tile
 * was not touched, {@code 0x01} means it was. This codec is the single place
 * that knows about that convention, so {@link com.tileboard.serial.board.Board#fromWireBytes} can turn
 * the raw payload directly into a {@code Board<Boolean>} the game engine can
 * work with.
 */
public final class TileTouchCodec {

    private static final byte NOT_TOUCHED = 0x00;
    private static final byte TOUCHED = 0x01;

    private TileTouchCodec() {
    }

    public static TileCodec<Boolean> instance() {
        return TileCodec.of(
                touched -> Boolean.TRUE.equals(touched) ? TOUCHED : NOT_TOUCHED,
                wireValue -> (wireValue & 0xFF) != 0
        );
    }
}
