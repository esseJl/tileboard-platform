package com.tileboard.serial.board;

import com.tileboard.serial.exception.BoardException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoardTest {

    private enum SampleColor { OFF, RED, GREEN }

    private static final TileCodec<SampleColor> CODEC = TileCodec.of(
            color -> switch (color) {
                case OFF -> (byte) 0;
                case RED -> (byte) 1;
                case GREEN -> (byte) 2;
            },
            wireValue -> switch (wireValue) {
                case 1 -> SampleColor.RED;
                case 2 -> SampleColor.GREEN;
                default -> SampleColor.OFF;
            });

    @Test
    void getAndSetRoundTrip() {
        Board<SampleColor> board = new Board<>(3, 2, SampleColor.OFF);
        board.set(0, 2, SampleColor.RED);

        assertEquals(SampleColor.RED, board.get(0, 2));
        assertEquals(SampleColor.OFF, board.get(1, 0));
    }

    @Test
    void toWireBytesFlattensRowMajor() {
        Board<SampleColor> board = new Board<>(2, 2, SampleColor.OFF);
        board.set(0, 0, SampleColor.RED);
        board.set(0, 1, SampleColor.GREEN);
        board.set(1, 0, SampleColor.GREEN);
        board.set(1, 1, SampleColor.RED);

        assertArrayEquals(new byte[]{1, 2, 2, 1}, board.toWireBytes(CODEC));
    }

    @Test
    void fromWireBytesRebuildsTheSameBoard() {
        byte[] flat = {0, 1, 2, 0, 1, 2};
        Board<SampleColor> board = Board.fromWireBytes(flat, 3, 2, CODEC);

        assertEquals(SampleColor.OFF, board.get(0, 0));
        assertEquals(SampleColor.RED, board.get(0, 1));
        assertEquals(SampleColor.GREEN, board.get(0, 2));
        assertArrayEquals(flat, board.toWireBytes(CODEC));
    }

    @Test
    void fromWireBytesRejectsMismatchedLength() {
        byte[] tooShort = {0, 1, 2};
        assertThrows(BoardException.class, () -> Board.fromWireBytes(tooShort, 3, 2, CODEC));
    }

    @Test
    void outOfBoundsAccessThrows() {
        Board<SampleColor> board = new Board<>(2, 2, SampleColor.OFF);
        assertThrows(BoardException.class, () -> board.get(5, 0));
    }

    @Test
    void positionsWhereFindsMatchingTilesInRowMajorOrder() {
        Board<SampleColor> board = new Board<>(3, 2, SampleColor.OFF);
        board.set(0, 2, SampleColor.RED);
        board.set(1, 0, SampleColor.RED);

        assertEquals(
                java.util.List.of(new Position(0, 2), new Position(1, 0)),
                board.positionsWhere(color -> color == SampleColor.RED));
    }

    @Test
    void positionsWhereReturnsEmptyListWhenNothingMatches() {
        Board<SampleColor> board = new Board<>(2, 2, SampleColor.OFF);
        assertTrue(board.positionsWhere(color -> color == SampleColor.GREEN).isEmpty());
    }

    @Test
    void touchBoardDecodedWithBooleanStateCodecReportsTouchedPositions() {
        byte[] flat = {0, 1, 0, 1};
        Board<Boolean> touchBoard = Board.fromWireBytes(flat, 2, 2, TileCodec.booleanState());

        assertEquals(
                java.util.List.of(new Position(0, 1), new Position(1, 1)),
                touchBoard.positionsWhere(Boolean.TRUE::equals));
    }
}
