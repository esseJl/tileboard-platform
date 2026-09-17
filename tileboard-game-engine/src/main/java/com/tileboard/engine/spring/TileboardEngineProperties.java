package com.tileboard.engine.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * All engine settings that can be configured via {@code application.yml}:
 *
 * <pre>
 * tileboard:
 *   engine:
 *     serial-port: COM3
 *     board-width: 8
 *     board-height: 8
 *     tick-interval: 100ms
 *     handshake-enabled: true
 * </pre>
 */
@ConfigurationProperties(prefix = "tileboard.engine")
public final class TileboardEngineProperties {

    /** System port name, e.g. {@code COM3} or {@code /dev/ttyUSB0}. */
    private String serialPort = "COM3";

    private int  boardWidth  = 8;
    private int  boardHeight = 8;

    /** Interval between onTick() calls. */
    private Duration tickInterval = Duration.ofMillis(100);

    /** Whether to enable the tile-id addressing handshake automatically. */
    private boolean handshakeEnabled = true;

    /** Minimum sequence length for the sequential id validator. */
    private int handshakeMinimumSequence = 2;

    public String   getSerialPort()               { return serialPort; }
    public void     setSerialPort(String v)        { this.serialPort = v; }
    public int      getBoardWidth()               { return boardWidth; }
    public void     setBoardWidth(int v)          { this.boardWidth = v; }
    public int      getBoardHeight()              { return boardHeight; }
    public void     setBoardHeight(int v)         { this.boardHeight = v; }
    public Duration getTickInterval()             { return tickInterval; }
    public void     setTickInterval(Duration v)   { this.tickInterval = v; }
    public boolean  isHandshakeEnabled()          { return handshakeEnabled; }
    public void     setHandshakeEnabled(boolean v){ this.handshakeEnabled = v; }
    public int      getHandshakeMinimumSequence() { return handshakeMinimumSequence; }
    public void     setHandshakeMinimumSequence(int v){ this.handshakeMinimumSequence = v; }
}