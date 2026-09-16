package com.tileboard.serial.gateway.handshake;

/**
 * The addressing information the tile controller needs when it asks the
 * host to (re)assign tile ids: the total number of tiles on the board and
 * how many of them make up one row.
 */
public record DeviceAddress(int totalTiles, int tilesPerRow) {

    public DeviceAddress {
        if (totalTiles <= 0 || totalTiles > 255) {
            throw new IllegalArgumentException("totalTiles must be in [1, 255], got " + totalTiles);
        }
        if (tilesPerRow <= 0 || tilesPerRow > 255) {
            throw new IllegalArgumentException("tilesPerRow must be in [1, 255], got " + tilesPerRow);
        }
    }

    public static DeviceAddress forBoard(int width, int height) {
        return new DeviceAddress(width * height, width);
    }

    /** The 2-byte payload the protocol expects for an {@code ID}/{@code SET} frame. */
    public byte[] toPayload() {
        return new byte[]{(byte) totalTiles, (byte) tilesPerRow};
    }
}
