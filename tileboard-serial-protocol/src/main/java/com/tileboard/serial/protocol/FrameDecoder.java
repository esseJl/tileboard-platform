package com.tileboard.serial.protocol;

import java.util.List;

/**
 * Decodes raw bytes read from a serial transport into complete {@link Frame}
 * instances. Serial reads rarely align with frame boundaries - a single
 * {@code serialEvent} can contain half a frame, several frames, or the tail
 * of a frame started in a previous read - so implementations are expected to
 * buffer internally and only emit frames once they are fully received and
 * validated.
 *
 * <p>A single implementation instance is stateful (it owns the accumulation
 * buffer) and is therefore expected to be dedicated to one transport /
 * connection; it is still a functional interface because callers only ever
 * need to invoke {@link #decode(byte[])}.
 */
@FunctionalInterface
public interface FrameDecoder {

    /**
     * Feeds newly received bytes into the decoder.
     *
     * @param chunk bytes read from the transport since the last call
     * @return every complete frame that became available as a result of this chunk, in order.
     *         Never {@code null}; empty if no complete frame is available yet.
     */
    List<Frame> decode(byte[] chunk);
}
