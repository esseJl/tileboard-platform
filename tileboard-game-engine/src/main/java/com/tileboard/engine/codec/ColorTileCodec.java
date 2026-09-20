package com.tileboard.engine.codec;

import com.tileboard.engine.model.TileColor;
import com.tileboard.serial.board.TileCodec;

/**
 * Bridges {@link TileColor} wire codes to the {@link TileCodec} contract
 * expected by the protocol library.
 */
public final class ColorTileCodec {

    private ColorTileCodec() {
    }

    public static TileCodec<TileColor> instance() {
        return TileCodec.of(color -> (byte) color.wireCode(), wire -> TileColor.fromWireCode(wire & 0xFF));
    }
}