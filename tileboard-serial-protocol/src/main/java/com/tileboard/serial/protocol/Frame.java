package com.tileboard.serial.protocol;

import java.util.Arrays;
import java.util.Objects;

/**
 * An immutable, already-validated protocol frame: a {@link Command}, a
 * {@link CommandType} and an arbitrary payload. This is the unit of exchange
 * between the {@link FrameEncoder}/{@link FrameDecoder} pair (see
 * {@link DefaultFrameCodec}) and everything above it (gateway, handshake
 * policy, application code) - nobody above the codec ever touches raw bytes.
 */
public final class Frame {

    private final Command command;
    private final CommandType commandType;
    private final byte[] payload;

    private Frame(Command command, CommandType commandType, byte[] payload) {
        this.command = Objects.requireNonNull(command, "command");
        this.commandType = Objects.requireNonNull(commandType, "commandType");
        this.payload = payload.clone();
    }

    public static Frame of(Command command, CommandType commandType, byte[] payload) {
        return new Frame(command, commandType, payload == null ? new byte[0] : payload);
    }

    public static Frame of(Command command, CommandType commandType) {
        return of(command, commandType, new byte[0]);
    }

    public Command command() {
        return command;
    }

    public CommandType commandType() {
        return commandType;
    }

    /** Defensive copy of the payload - callers may not mutate the frame's internal state. */
    public byte[] payload() {
        return payload.clone();
    }

    public int payloadLength() {
        return payload.length;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Frame frame)) return false;
        return command == frame.command
                && commandType == frame.commandType
                && Arrays.equals(payload, frame.payload);
    }

    @Override
    public int hashCode() {
        return Objects.hash(command, commandType, Arrays.hashCode(payload));
    }

    @Override
    public String toString() {
        return "Frame{" +
                "command=" + command +
                ", commandType=" + commandType +
                ", payload=" + Arrays.toString(payload) +
                '}';
    }
}
