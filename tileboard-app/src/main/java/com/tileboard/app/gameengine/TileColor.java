package com.tileboard.app.gameengine;

import com.tileboard.serial.exception.ProtocolException;

import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The tile colors the controller firmware understands, with their exact
 * wire byte values (0-6, per the legacy firmware's convention - see
 * {@code BaseGameConfig.RED/GREEN/BLUE/...} in the previous implementation).
 * Any future game needing colored output reuses this rather than each game
 * re-declaring its own numbering, which is how mismatched palettes cause a
 * "green" written by one game to show up as PINK sent by another.
 */
public enum TileColor {

    OFF(0),
    RED(1),
    GREEN(2),
    BLUE(3),
    PINK(4),
    LIGHT_BLUE(5),
    WHITE(6);

    private final int code;

    TileColor(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    private static final Map<Integer, TileColor> BY_CODE = Stream.of(values())
            .collect(Collectors.toUnmodifiableMap(TileColor::code, c -> c));

    /** @throws ProtocolException if the byte doesn't correspond to a known color */
    public static TileColor fromCode(int code) {
        TileColor color = BY_CODE.get(code & 0xFF);
        if (color == null) {
            throw new ProtocolException("Unknown tile color code: " + (code & 0xFF));
        }
        return color;
    }
}
