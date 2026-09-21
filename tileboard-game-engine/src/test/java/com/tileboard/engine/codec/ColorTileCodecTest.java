package com.tileboard.engine.codec;

import com.tileboard.engine.model.TileColor;
import com.tileboard.serial.board.TileCodec;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ColorTileCodecTest {
    @Test
    void roundTripPreservesColor() {
        TileCodec<TileColor> codec = ColorTileCodec.instance();
        for (TileColor c : TileColor.values()) {
            byte wire = codec.encode(c);
            assertEquals(c, codec.decode(wire));
        }
    }

    @Test
    void unknownWireCodeFallsBackToOff() {
        assertEquals(TileColor.OFF, TileColor.fromWireCode(250));
    }
}
