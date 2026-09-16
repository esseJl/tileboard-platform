package com.tileboard.app.gameengine;

import com.tileboard.serial.board.TileCodec;

/** The single place that bridges {@link TileColor} to the wire format via {@link TileCodec}. */
public final class TileColors {

    private static final TileCodec<TileColor> CODEC = TileCodec.of(
            color -> (byte) color.code(),
            wireValue -> TileColor.fromCode(wireValue & 0xFF));

    private TileColors() {
    }

    public static TileCodec<TileColor> codec() {
        return CODEC;
    }
}
