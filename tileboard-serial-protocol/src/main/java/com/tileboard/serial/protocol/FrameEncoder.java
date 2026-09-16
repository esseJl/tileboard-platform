package com.tileboard.serial.protocol;

/**
 * Encodes a {@link Frame} into the raw bytes that go out on the wire.
 * A functional interface so callers can supply an alternative wire format
 * (e.g. for a future protocol revision) without touching the rest of the
 * library.
 */
@FunctionalInterface
public interface FrameEncoder {

    byte[] encode(Frame frame);
}
