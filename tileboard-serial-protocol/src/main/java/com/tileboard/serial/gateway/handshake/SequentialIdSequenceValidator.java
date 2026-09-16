package com.tileboard.serial.gateway.handshake;

/**
 * Default {@link SequenceValidator}: the payload is considered valid when it
 * has at least {@link #minimumLength} bytes and every byte except the last
 * one equals its 1-based position (i.e. {@code payload[i] == i + 1}). This
 * mirrors the tile ids being handed out in row-major order starting at 1.
 */
public final class SequentialIdSequenceValidator implements SequenceValidator {

    private final int minimumLength;

    public SequentialIdSequenceValidator(int minimumLength) {
        if (minimumLength < 1) {
            throw new IllegalArgumentException("minimumLength must be >= 1, got " + minimumLength);
        }
        this.minimumLength = minimumLength;
    }

    @Override
    public boolean isValid(byte[] payload) {
        if (payload.length < minimumLength) {
            return false;
        }
        for (int i = 0; i < payload.length - 1; i++) {
            if ((payload[i] & 0xFF) != i + 1) {
                return false;
            }
        }
        return true;
    }
}
