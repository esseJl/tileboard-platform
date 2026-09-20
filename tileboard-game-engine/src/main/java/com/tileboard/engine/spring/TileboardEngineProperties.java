package com.tileboard.engine.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Engine-only settings, configurable via {@code application.yml}:
 *
 * <pre>
 * tileboard:
 *   engine:
 *     tick-interval: 100ms
 * </pre>
 *
 * <p>Deliberately does <strong>not</strong> contain anything about the
 * physical serial connection (port name, baud rate, board geometry, id
 * handshake, ...). This library never opens a port itself - it only ever
 * receives an already-open {@link com.tileboard.serial.gateway.TileGatewayClient}
 * via {@link GatewayConnectedEvent}, published by whichever application
 * component owns the connection lifecycle. Owning/opening the serial port,
 * and everything hardware-specific about it, is the application's
 * responsibility (see {@code tileboard.serial.*} and the port/device REST
 * endpoints in {@code tileboard-app}).
 *
 * <p>An earlier version of this class duplicated {@code serial-port},
 * {@code board-width}, {@code board-height} and the handshake settings here
 * too, which made the engine open a second, independent connection to the
 * same hardware instead of reusing the one the application already manages -
 * that duplication has been removed.
 */
@ConfigurationProperties(prefix = "tileboard.engine")
public final class TileboardEngineProperties {
    private Duration tickInterval = Duration.ofMillis(100);
    private Duration sessionTtl = Duration.ofHours(1);

    public Duration getTickInterval() {
        return tickInterval;
    }

    public void setTickInterval(Duration v) {
        this.tickInterval = v;
    }

    public Duration getSessionTtl() {
        return sessionTtl;
    }

    public void setSessionTtl(Duration v) {
        this.sessionTtl = v;
    }
}
