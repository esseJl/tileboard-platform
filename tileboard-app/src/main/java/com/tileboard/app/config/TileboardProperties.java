package com.tileboard.app.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Application-level configuration for talking to the tile board. Nothing
 * here is a protocol constant (those live in the library); these are the
 * knobs an operator may reasonably want to change per deployment/hardware
 * revision without touching code.
 *
 * @param baudRate                serial line speed expected by the controller firmware
 * @param dataBits                serial data bits
 * @param stopBits                serial stop bits (1 or 2)
 * @param readTimeoutMillis       read timeout applied when opening a port
 * @param writeTimeoutMillis      write timeout applied when opening a port
 * @param handshakeMinSequence    minimum accepted length for the id-assignment sequence
 *                                (see {@code SequentialIdSequenceValidator}); a sensible
 *                                default is derived from the board size if not set (0 = auto)
 */
@ConfigurationProperties(prefix = "tileboard.serial")
public record TileboardProperties(
        int baudRate,
        int dataBits,
        int stopBits,
        int readTimeoutMillis,
        int writeTimeoutMillis,
        int handshakeMinSequence
) {

    public TileboardProperties {
        if (baudRate <= 0) baudRate = 115_200;
        if (dataBits <= 0) dataBits = 8;
        if (stopBits <= 0) stopBits = 1;
        if (readTimeoutMillis <= 0) readTimeoutMillis = 50;
        if (writeTimeoutMillis <= 0) writeTimeoutMillis = 50;
        if (handshakeMinSequence < 0) handshakeMinSequence = 0;
    }
}
