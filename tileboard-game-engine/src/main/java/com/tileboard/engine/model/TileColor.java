package com.tileboard.engine.model;

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
    Yellow(6),
    WHITE(7);

    private final int wireCode;

    TileColor(int wireCode) { this.wireCode = wireCode; }

    public int wireCode() { return wireCode; }

    public static TileColor fromWireCode(int code) {
        for (TileColor c : values()) {
            if (c.wireCode == (code & 0xFF)) return c;
        }
        return OFF;
    }
}
