package com.tileboard.serial.protocol;

import com.tileboard.serial.exception.ProtocolException;

import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The high level command carried in byte 2 of every frame.
 *
 * <p>Each constant carries an explicit {@link #code()} instead of relying on
 * {@code ordinal()}. This is intentional: {@code ordinal()} changes silently
 * if a constant is ever reordered or one is inserted in the middle, which
 * would corrupt the wire protocol without a compile error. Binding the wire
 * value explicitly makes the protocol robust to future refactors of this enum.
 */
public enum Command {

    INTRODUCTION(0),
    DATA_IN(1),
    DATA_OUT(2),
    ID(3),
    STOP(4),
    START(5),
    COMMAND(6),
    RESET_PROGRAM(7),
    CLEAR_ID(8);

    private final int code;

    Command(int code) {
        this.code = code;
    }

    /** The single-byte value transmitted on the wire for this command. */
    public int code() {
        return code;
    }

    private static final Map<Integer, Command> BY_CODE = Stream.of(values())
            .collect(Collectors.toUnmodifiableMap(Command::code, c -> c));

    /**
     * Resolves the {@link Command} for a raw wire byte.
     *
     * @throws ProtocolException if the byte does not correspond to a known command
     */
    public static Command fromCode(int code) {
        Command command = BY_CODE.get(code & 0xFF);
        if (command == null) {
            throw new ProtocolException("Unknown command code: " + (code & 0xFF));
        }
        return command;
    }
}
