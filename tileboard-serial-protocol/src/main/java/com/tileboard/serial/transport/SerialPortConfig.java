package com.tileboard.serial.transport;

/**
 * Line settings used to open a {@link SerialTransport}. Nothing here is
 * hard-coded by the library - every value is supplied by the caller (or the
 * sensible defaults below, which callers are free to override).
 */
public final class SerialPortConfig {

    private final int baudRate;
    private final int dataBits;
    private final int stopBits;
    private final Parity parity;
    private final FlowControl flowControl;
    private final int readTimeoutMillis;
    private final int writeTimeoutMillis;

    private SerialPortConfig(Builder builder) {
        this.baudRate = builder.baudRate;
        this.dataBits = builder.dataBits;
        this.stopBits = builder.stopBits;
        this.parity = builder.parity;
        this.flowControl = builder.flowControl;
        this.readTimeoutMillis = builder.readTimeoutMillis;
        this.writeTimeoutMillis = builder.writeTimeoutMillis;
    }

    public int baudRate() {
        return baudRate;
    }

    public int dataBits() {
        return dataBits;
    }

    public int stopBits() {
        return stopBits;
    }

    public Parity parity() {
        return parity;
    }

    public FlowControl flowControl() {
        return flowControl;
    }

    public int readTimeoutMillis() {
        return readTimeoutMillis;
    }

    public int writeTimeoutMillis() {
        return writeTimeoutMillis;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 115200-8-N-1, the settings the tile controller firmware expects; override as needed. */
    public static SerialPortConfig defaults() {
        return builder().build();
    }

    public static final class Builder {
        private int baudRate = 115200;
        private int dataBits = 8;
        private int stopBits = 1;
        private Parity parity = Parity.NONE;
        private final FlowControl flowControl = FlowControl.NONE;
        private int readTimeoutMillis = 50;
        private int writeTimeoutMillis = 50;

        public Builder baudRate(int baudRate) {
            this.baudRate = baudRate;
            return this;
        }

        public Builder dataBits(int dataBits) {
            this.dataBits = dataBits;
            return this;
        }

        public Builder stopBits(int stopBits) {
            this.stopBits = stopBits;
            return this;
        }

        public Builder parity(Parity parity) {
            this.parity = parity;
            return this;
        }

        public Builder readTimeoutMillis(int readTimeoutMillis) {
            this.readTimeoutMillis = readTimeoutMillis;
            return this;
        }

        public Builder writeTimeoutMillis(int writeTimeoutMillis) {
            this.writeTimeoutMillis = writeTimeoutMillis;
            return this;
        }

        public SerialPortConfig build() {
            return new SerialPortConfig(this);
        }

    }
}
