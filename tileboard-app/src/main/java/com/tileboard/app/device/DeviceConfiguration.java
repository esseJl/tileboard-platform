package com.tileboard.app.device;

/**
 * The physical geometry of the tile board: how many tiles wide and tall it
 * is. This is the one piece of information every other module (serial
 * handshake, game engine) needs before it can do anything useful, so it is
 * modeled as its own small, immutable value rather than bolted onto a
 * bigger "settings" object.
 */
public record DeviceConfiguration(int width, int height) {

    public DeviceConfiguration {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException(
                    "width and height must both be > 0, got width=" + width + ", height=" + height);
        }
        if (width * height > 255) {
            // The wire handshake (see com.tileboard.serial.gateway.handshake.DeviceAddress)
            // encodes the total tile count in a single byte, so this is a hard
            // protocol ceiling, not an arbitrary application choice.
            throw new IllegalArgumentException(
                    "width * height must be <= 255 (protocol addressing limit), got " + (width * height));
        }
    }

    public int tileCount() {
        return width * height;
    }
}
