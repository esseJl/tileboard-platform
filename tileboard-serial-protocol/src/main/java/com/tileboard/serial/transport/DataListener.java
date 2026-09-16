package com.tileboard.serial.transport;

/**
 * Notified whenever new bytes are available on a {@link SerialTransport}.
 * Implementations should return quickly - hand off to the {@link com.tileboard.serial.protocol.FrameDecoder}
 * and return, doing any heavier processing on a separate thread/executor.
 */
@FunctionalInterface
public interface DataListener {

    void onDataReceived(byte[] data);
}
