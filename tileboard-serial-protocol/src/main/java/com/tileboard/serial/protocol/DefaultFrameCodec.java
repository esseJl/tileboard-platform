package com.tileboard.serial.protocol;

import com.tileboard.serial.exception.InvalidFrameException;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import static com.tileboard.serial.protocol.ProtocolConstants.*;

/**
 * Reference implementation of the tile board wire protocol described in
 * {@link ProtocolConstants}.
 *
 * <p>{@link #encode(Frame)} is a pure, stateless function. {@link #decode(byte[])}
 * is stateful: it accumulates bytes across calls so a frame split across two
 * serial reads (or several frames arriving in one read) are both handled
 * correctly, and it resynchronizes on the next {@link ProtocolConstants#START_BYTE}
 * if it ever encounters bytes that don't form a valid frame.
 *
 * <p>One instance must be dedicated to a single logical input stream (i.e. one
 * per {@code SerialTransport} being read from); it is not meant to be shared
 * across independent connections.
 */
public final class DefaultFrameCodec implements FrameEncoder, FrameDecoder {

    /**
     * Practical sanity ceiling for a payload length read off the wire while
     * resynchronizing. {@link ProtocolConstants#MAX_PAYLOAD_LENGTH} (65535)
     * is the field's technical maximum and therefore useless as a guard here
     * - a 2-byte length can never exceed it, so a check against it would
     * never trigger. Real tile-board frames are tiny (at most a few hundred
     * bytes; see {@code DeviceAddress}'s 255-tile ceiling), so a stray
     * START/SEPARATOR match from line noise that happens to decode a huge
     * "length" is almost certainly not a real frame. Without this, such a
     * match would make the decoder wait indefinitely for tens of thousands
     * of bytes that will never legitimately arrive, silently absorbing every
     * subsequent real frame as "still part of" that one bogus frame.
     */
    private static final int PLAUSIBLE_PAYLOAD_LENGTH_CEILING = 4096;

    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

    @Override
    public byte[] encode(Frame frame) {
        byte[] payload = frame.payload();
        if (payload.length > MAX_PAYLOAD_LENGTH) {
            throw new InvalidFrameException("protocol.payload_too_large",
                    new Object[] { payload.length, MAX_PAYLOAD_LENGTH },
                    "Payload of " + payload.length + " bytes exceeds the maximum of " + MAX_PAYLOAD_LENGTH);
        }

        byte[] out = new byte[payload.length + FRAME_OVERHEAD_BYTES];
        out[0] = START_BYTE;
        out[1] = SEPARATOR_BYTE;
        out[2] = (byte) frame.command().code();
        out[3] = (byte) frame.commandType().code();
        out[4] = (byte) (payload.length >> 8);
        out[5] = (byte) payload.length;
        System.arraycopy(payload, 0, out, 6, payload.length);
        out[out.length - 1] = END_BYTE;
        return out;
    }

    @Override
    public synchronized List<Frame> decode(byte[] chunk) {
        buffer.writeBytes(chunk);
        List<Frame> frames = new ArrayList<>();

        byte[] data = buffer.toByteArray();
        int consumedUpTo = 0;

        try {
            while (true) {
                int start = indexOfFrameStart(data, consumedUpTo);
                if (start < 0) {
                    // No START/SEPARATOR pair at all: nothing usable is left.
                    consumedUpTo = data.length;
                    break;
                }

                int available = data.length - start;
                if (available < 6) {
                    // Not enough bytes yet to even read the length field.
                    consumedUpTo = start;
                    break;
                }

                int payloadLength = ((data[start + 4] & 0xFF) << 8) | (data[start + 5] & 0xFF);
                if (payloadLength > PLAUSIBLE_PAYLOAD_LENGTH_CEILING) {
                    // A real frame can never declare a length this large: this
                    // START/SEPARATOR match was noise, not a genuine frame.
                    // Resync one byte later instead of waiting forever for
                    // bytes that will never legitimately arrive.
                    consumedUpTo = start + 1;
                    continue;
                }
                int totalFrameLength = payloadLength + FRAME_OVERHEAD_BYTES;

                if (available < totalFrameLength) {
                    // Frame header is known but the body hasn't fully arrived yet.
                    consumedUpTo = start;
                    break;
                }

                int endIndex = start + totalFrameLength - 1;
                if ((data[endIndex] & 0xFF) != (END_BYTE & 0xFF)) {
                    // Declared length didn't line up with an END_BYTE: this wasn't a
                    // real frame start, just a stray 0xFC. Resync one byte later.
                    consumedUpTo = start + 1;
                    continue;
                }

                Command command;
                CommandType commandType;
                try {
                    command = Command.fromCode(data[start + 2]);
                    commandType = CommandType.fromCode(data[start + 3]);
                } catch (RuntimeException e) {
                    // Length and END_BYTE happened to line up but the command/type
                    // bytes are garbage: still not a real frame. Resync one byte
                    // later rather than letting this escape the loop - an
                    // exception escaping here would skip the buffer-trimming
                    // step below, so the same bad bytes would be reprocessed
                    // (and fail again) on every future call, forever.
                    consumedUpTo = start + 1;
                    continue;
                }

                byte[] payload = new byte[payloadLength];
                System.arraycopy(data, start + 6, payload, 0, payloadLength);

                frames.add(Frame.of(command, commandType, payload));
                consumedUpTo = start + totalFrameLength;
            }
        } finally {
            // Always trim, even if something unexpected above threw: falling
            // back to "drop everything buffered so far" beats leaving a
            // decoder that will throw on the exact same bytes forever.
            buffer.reset();
            if (consumedUpTo < data.length) {
                buffer.write(data, consumedUpTo, data.length - consumedUpTo);
            }
        }

        return frames;
    }

    /** Finds the next {@code START_BYTE} immediately followed by {@code SEPARATOR_BYTE}, from {@code from}. */
    private int indexOfFrameStart(byte[] data, int from) {
        for (int i = from; i < data.length - 1; i++) {
            if (data[i] == START_BYTE && data[i + 1] == SEPARATOR_BYTE) {
                return i;
            }
        }
        return -1;
    }

    /** Discards any partially-buffered, never-completed frame. Useful after a transport reconnect. */
    public synchronized void reset() {
        buffer.reset();
    }
}
