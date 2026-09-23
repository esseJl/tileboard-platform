package com.tileboard.serial.protocol;

import com.tileboard.serial.exception.ProtocolException;

import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Qualifies a {@link Command} (byte 3 of every frame): whether the frame is
 * setting a value, requesting one, clearing it, and so on.
 *
 * <p>As with {@link Command}, the wire value is an explicit field rather than
 * {@code ordinal()} so the enum can evolve safely.
 */
public enum CommandType {

    SET(0),
    GET(1),
    CLEAR(2),
    SET_EXTENDED(3),
    NA(4),
    WAIT(5);

    private final int code;

    CommandType(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    private static final Map<Integer, CommandType> BY_CODE = Stream.of(values())
            .collect(Collectors.toUnmodifiableMap(CommandType::code, c -> c));

    /**
     * Resolves the {@link CommandType} for a raw wire byte.
     *
     * @throws ProtocolException if the byte does not correspond to a known command type
     */
    public static CommandType fromCode(int code) {
        CommandType type = BY_CODE.get(code & 0xFF);
        if (type == null) {
            throw new ProtocolException("protocol.unknown_command_type", new Object[] { code & 0xFF },
                    "Unknown command type code: " + (code & 0xFF));
        }
        return type;
    }
}
