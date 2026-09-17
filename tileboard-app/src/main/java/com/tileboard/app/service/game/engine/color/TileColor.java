package com.tileboard.app.service.game.engine.color;

import com.tileboard.serial.board.TileCodec;

/**
 * Canonical palette used by most games. Wire values match the original
 * hardware convention from the legacy project (1=RED ... 6=WHITE, 0=OFF).
 */
public enum TileColor {
    OFF((byte) 0),
    RED((byte) 1),
    GREEN((byte) 2),
    BLUE((byte) 3),
    PINK((byte) 4),
    LIGHT_BLUE((byte) 5),
    Yellow((byte) 6),
    WHITE((byte) 7);

    private final byte wireValue;

    TileColor(byte wireValue) {
        this.wireValue = wireValue;
    }

    public byte wireValue() {
        return wireValue;
    }

    public static TileColor fromWire(byte value) {
        for (TileColor c : values()) {
            if (c.wireValue == value) {
                return c;
            }
        }
        return OFF;
    }

    /** Shared codec instance – safe for concurrent use. */
    public static final TileCodec<TileColor> CODEC = TileCodec.of(
            TileColor::wireValue,
            TileColor::fromWire
    );
}
