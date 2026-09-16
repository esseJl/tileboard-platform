package com.tileboard.serial.board;

import java.util.Objects;

/**
 * Pairs a {@link TileEncoder} and a {@link TileDecoder} for a given tile type
 * {@code T}. This is how an application plugs its own notion of "tile" (a
 * color enum, an intensity level, a custom class...) into the board
 * (de)serialization used by the gateway - the library never hard-codes a
 * color palette or tile representation.
 *
 * @param <T> the application's tile type
 */
public final class TileCodec<T> {

    private final TileEncoder<T> encoder;
    private final TileDecoder<T> decoder;

    private TileCodec(TileEncoder<T> encoder, TileDecoder<T> decoder) {
        this.encoder = Objects.requireNonNull(encoder, "encoder");
        this.decoder = Objects.requireNonNull(decoder, "decoder");
    }

    public static <T> TileCodec<T> of(TileEncoder<T> encoder, TileDecoder<T> decoder) {
        return new TileCodec<>(encoder, decoder);
    }

    /** A trivial codec for boards that already work directly in raw device bytes. */
    public static TileCodec<Byte> identity() {
        return of(Byte::byteValue, Byte::valueOf);
    }

    /**
     * A codec for boards whose wire representation is a single flag byte per
     * tile - {@code 0} for one state, any non-zero value for the other. This
     * is the common convention for touch/press/on-off style {@code DATA_IN}
     * and {@code DATA_OUT} payloads (e.g. "was this tile touched?"), so it is
     * provided here rather than every application re-deriving it.
     *
     * @param offValue the wire byte written for {@code false}
     * @param onValue  the wire byte written for {@code true}
     */
    public static TileCodec<Boolean> booleanState(byte offValue, byte onValue) {
        return of(
                state -> Boolean.TRUE.equals(state) ? onValue : offValue,
                wireValue -> wireValue != 0
        );
    }

    /** {@link #booleanState(byte, byte)} with the conventional {@code 0}/{@code 1} wire values. */
    public static TileCodec<Boolean> booleanState() {
        return booleanState((byte) 0, (byte) 1);
    }

    public byte encode(T tile) {
        return encoder.encode(tile);
    }

    public T decode(byte wireValue) {
        return decoder.decode(wireValue);
    }
}
