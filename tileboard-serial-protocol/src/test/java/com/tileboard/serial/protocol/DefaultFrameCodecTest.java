package com.tileboard.serial.protocol;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultFrameCodecTest {

    @Test
    void encodesFrameWithProtocolHeaderAndTrailer() {
        DefaultFrameCodec codec = new DefaultFrameCodec();
        Frame frame = Frame.of(Command.DATA_OUT, CommandType.SET, new byte[]{1, 2, 3});

        byte[] wire = codec.encode(frame);

        assertEquals(3 + ProtocolConstants.FRAME_OVERHEAD_BYTES, wire.length);
        assertEquals(ProtocolConstants.START_BYTE, wire[0]);
        assertEquals(ProtocolConstants.SEPARATOR_BYTE, wire[1]);
        assertEquals((byte) Command.DATA_OUT.code(), wire[2]);
        assertEquals((byte) CommandType.SET.code(), wire[3]);
        assertEquals(0, wire[4]);
        assertEquals(3, wire[5]);
        assertArrayEquals(new byte[]{1, 2, 3}, java.util.Arrays.copyOfRange(wire, 6, 9));
        assertEquals(ProtocolConstants.END_BYTE, wire[9]);
    }

    @Test
    void decodesExactlyWhatWasEncoded() {
        DefaultFrameCodec codec = new DefaultFrameCodec();
        Frame original = Frame.of(Command.ID, CommandType.SET, new byte[]{9, 1});

        List<Frame> decoded = codec.decode(codec.encode(original));

        assertEquals(1, decoded.size());
        assertEquals(original, decoded.get(0));
    }

    @Test
    void decodesFrameSplitAcrossMultipleReads() {
        DefaultFrameCodec codec = new DefaultFrameCodec();
        Frame original = Frame.of(Command.DATA_IN, CommandType.SET, new byte[]{1, 2, 3, 4, 5});
        byte[] wire = codec.encode(original);

        int splitPoint = 4;
        assertTrue(codec.decode(java.util.Arrays.copyOfRange(wire, 0, splitPoint)).isEmpty());
        List<Frame> decoded = codec.decode(java.util.Arrays.copyOfRange(wire, splitPoint, wire.length));

        assertEquals(1, decoded.size());
        assertEquals(original, decoded.get(0));
    }

    @Test
    void decodesMultipleFramesDeliveredInOneChunk() {
        DefaultFrameCodec codec = new DefaultFrameCodec();
        Frame first = Frame.of(Command.STOP, CommandType.SET);
        Frame second = Frame.of(Command.START, CommandType.SET);

        byte[] chunk = concat(codec.encode(first), codec.encode(second));
        List<Frame> decoded = codec.decode(chunk);

        assertEquals(List.of(first, second), decoded);
    }

    @Test
    void resynchronizesAfterGarbageBytesContainingAStrayStartByte() {
        DefaultFrameCodec codec = new DefaultFrameCodec();
        Frame frame = Frame.of(Command.RESET_PROGRAM, CommandType.NA);

        byte[] garbage = {ProtocolConstants.START_BYTE, 0x00, 0x11};
        byte[] chunk = concat(garbage, codec.encode(frame));

        List<Frame> decoded = codec.decode(chunk);

        assertEquals(1, decoded.size());
        assertEquals(frame, decoded.get(0));
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] result = new byte[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }
}
