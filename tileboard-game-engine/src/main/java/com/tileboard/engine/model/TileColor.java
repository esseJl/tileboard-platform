package com.tileboard.engine.model;

import java.util.Arrays;

/**
 * Application-level tile color. The engine never hard-codes a palette;
 * games supply their own {@link com.tileboard.engine.codec.ColorTileCodec}.
 * This enum is the default, extensible starting point.
 */
public enum TileColor {
    OFF(0),
    RED(1),
    GREEN(2),
    BLUE(3),
    PINK(4),
    LIGHT_BLUE(5),
    YELLOW(6),
    WHITE(7);

    private static final TileColor[] BY_WIRE_CODE = buildLookupTable();
    private final int wireCode;

    TileColor(int wireCode) {
        this.wireCode = wireCode;
    }

    private static TileColor[] buildLookupTable() {
        TileColor[] table = new TileColor[256];
        Arrays.fill(table, OFF);
        for (TileColor c : values()) {
            table[c.wireCode & 0xFF] = c;
        }
        return table;
    }

    public static TileColor fromWireCode(int code) {
        return BY_WIRE_CODE[code & 0xFF];
    }

    public int wireCode() {
        return wireCode;
    }
}
