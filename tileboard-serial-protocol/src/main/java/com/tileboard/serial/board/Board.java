package com.tileboard.serial.board;

import com.tileboard.serial.exception.BoardException;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * A mutable {@code height x width} grid of application-level tile values.
 *
 * <p>{@code Board} itself knows nothing about colors, LEDs or the serial
 * protocol - it is a plain, reusable data structure. The bridge to the wire
 * format is done on demand via a {@link TileCodec} supplied by the caller
 * (see {@link #toWireBytes(TileCodec)} / {@link #fromWireBytes(byte[], int, int, TileCodec)}),
 * so the same class works for any device geometry or tile representation.
 *
 * @param <T> the application's tile type, e.g. an enum of colors
 */
public final class Board<T> {

    private final int width;
    private final int height;
    private final Object[][] tiles; // stored as Object[][] to keep the class allocation-free of T's class token

    public Board(int width, int height, Supplier<T> initialTileSupplier) {
        if (width <= 0 || height <= 0) {
            throw new BoardException("board.invalid_dimensions", new Object[] { width, height },
                    "width and height must both be > 0, got width=" + width + ", height=" + height);
        }
        Objects.requireNonNull(initialTileSupplier, "initialTileSupplier");
        this.width = width;
        this.height = height;
        this.tiles = new Object[height][width];
        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {
                tiles[row][col] = initialTileSupplier.get();
            }
        }
    }

    public Board(int width, int height, T initialTile) {
        this(width, height, () -> initialTile);
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public int area() {
        return width * height;
    }

    @SuppressWarnings("unchecked")
    public T get(int row, int col) {
        checkBounds(row, col);
        return (T) tiles[row][col];
    }

    public T get(Position position) {
        return get(position.row(), position.col());
    }

    public void set(int row, int col, T tile) {
        checkBounds(row, col);
        tiles[row][col] = tile;
    }

    public void set(Position position, T tile) {
        set(position.row(), position.col(), tile);
    }

    public void fill(T tile) {
        for (Object[] row : tiles) {
            java.util.Arrays.fill(row, tile);
        }
    }

    /** Invokes {@code action} for every (row, col, tile) in row-major order. */
    @SuppressWarnings("unchecked")
    public void forEach(TileConsumer<T> action) {
        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {
                action.accept(row, col, (T) tiles[row][col]);
            }
        }
    }

    /**
     * Every {@link Position} whose tile matches {@code predicate}, in
     * row-major order. Typical use is answering "which tile(s) changed?"
     * from a decoded board, e.g. {@code touchBoard.positionsWhere(Boolean.TRUE::equals)}
     * to find which tiles were touched.
     */
    public List<Position> positionsWhere(Predicate<T> predicate) {
        Objects.requireNonNull(predicate, "predicate");
        List<Position> matches = new ArrayList<>();
        forEach((row, col, tile) -> {
            if (predicate.test(tile)) {
                matches.add(new Position(row, col));
            }
        });
        return matches;
    }

    /** A deep, independent copy of this board. */
    public Board<T> copy() {
        Board<T> copy = new Board<>(width, height, () -> null);
        this.forEach(copy::set);
        return copy;
    }

    /**
     * Flattens this board into the row-major byte array expected on the wire,
     * using {@code codec} to translate each tile.
     */
    public byte[] toWireBytes(TileCodec<T> codec) {
        Objects.requireNonNull(codec, "codec");
        byte[] flat = new byte[area()];
        int index = 0;
        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {
                flat[index++] = codec.encode(get(row, col));
            }
        }
        return flat;
    }

    /**
     * Builds a board from a row-major flat byte array (as received in a
     * {@code DATA_IN}/{@code DATA_OUT} payload), using {@code codec} to
     * translate each byte back into an application tile value.
     *
     * @throws BoardException if {@code flat.length != width * height}
     */
    public static <T> Board<T> fromWireBytes(byte[] flat, int width, int height, TileCodec<T> codec) {
        Objects.requireNonNull(flat, "flat");
        Objects.requireNonNull(codec, "codec");
        if (flat.length != width * height) {
            throw new BoardException("board.byte_length_mismatch",
                    new Object[] { width * height, width, height, flat.length },
                    "Expected " + (width * height) + " bytes for a " + width + "x" + height +
                            " board but got " + flat.length);
        }
        Board<T> board = new Board<>(width, height, () -> null);
        int index = 0;
        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {
                board.tiles[row][col] = codec.decode(flat[index++]);
            }
        }
        return board;
    }

    private void checkBounds(int row, int col) {
        if (row < 0 || row >= height || col < 0 || col >= width) {
            throw new BoardException("board.position_out_of_bounds", new Object[] { row, col, width, height },
                    "Position (row=" + row + ", col=" + col +
                            ") is out of bounds for a " + width + "x" + height + " board");
        }
    }

    /** Callback used by {@link #forEach(TileConsumer)}. */
    @FunctionalInterface
    public interface TileConsumer<T> {
        void accept(int row, int col, T tile);
    }
}
