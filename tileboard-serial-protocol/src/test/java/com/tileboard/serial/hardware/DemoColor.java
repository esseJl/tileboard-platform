package com.tileboard.serial.hardware;

import com.tileboard.serial.board.TileCodec;

import java.util.List;

/**
 * A concrete color palette used purely for {@link TileboardHardwareIT}. It is
 * intentionally kept out of the main library sources (see the {@code board}
 * package's {@code TileEncoder}/{@code TileDecoder}) - the library itself
 * never hard-codes a palette. The wire values below match the original
 * firmware's color codes (0 = off, 1..6 = colors) purely so this test can
 * drive the real device.
 */
enum DemoColor {

    OFF(0),
    RED(1),
    GREEN(2),
    BLUE(3),
    PINK(4),
    LIGHT_BLUE(5),
    WHITE(6);

    private final int wireValue;

    DemoColor(int wireValue) {
        this.wireValue = wireValue;
    }

    /** Every color except {@link #OFF} - used by the "cycle through colors" step of the animation demo. */
    static List<DemoColor> visiblePalette() {
        return List.of(RED, GREEN, BLUE, PINK, LIGHT_BLUE, WHITE);
    }

    static TileCodec<DemoColor> codec() {
        return TileCodec.of(
                color -> (byte) color.wireValue,
                wire -> {
                    int value = wire & 0xFF;
                    for (DemoColor color : values()) {
                        if (color.wireValue == value) {
                            return color;
                        }
                    }
                    return OFF;
                });
    }
}
