package com.tileboard.engine.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "tileboard.engine")
public final class TileboardEngineProperties {

    private Duration tickInterval = Duration.ofMillis(100);
    private Duration sessionTtl = Duration.ofHours(1);
    private Duration frameReassemblyTimeout = Duration.ofMillis(500);
    private int eventBusQueueCapacity = 256;
    private int touchHistoryMaxSize = 2_000;

    public Duration getTickInterval() {
        return tickInterval;
    }

    public void setTickInterval(Duration tickInterval) {
        this.tickInterval = tickInterval;
    }

    public Duration getSessionTtl() {
        return sessionTtl;
    }

    public void setSessionTtl(Duration sessionTtl) {
        this.sessionTtl = sessionTtl;
    }

    public Duration getFrameReassemblyTimeout() {
        return frameReassemblyTimeout;
    }

    public void setFrameReassemblyTimeout(Duration v) {
        this.frameReassemblyTimeout = v;
    }

    public int getEventBusQueueCapacity() {
        return eventBusQueueCapacity;
    }

    public void setEventBusQueueCapacity(int v) {
        this.eventBusQueueCapacity = v;
    }

    public int getTouchHistoryMaxSize() {
        return touchHistoryMaxSize;
    }

    public void setTouchHistoryMaxSize(int v) {
        this.touchHistoryMaxSize = v;
    }
}