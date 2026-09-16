package com.tileboard.serial.gateway;

import com.tileboard.serial.protocol.Frame;

/**
 * Notified whenever a complete, valid {@link Frame} has been received from
 * the device. Registered on a {@link TileGatewayClient}; several listeners
 * can be registered at once (e.g. one per {@code Command} the application
 * cares about).
 */
@FunctionalInterface
public interface FrameListener {

    void onFrame(Frame frame);
}
