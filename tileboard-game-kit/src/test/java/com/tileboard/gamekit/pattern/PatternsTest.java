package com.tileboard.gamekit.pattern;

import com.tileboard.gamekit.time.RandomSource;
import com.tileboard.serial.board.Position;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PatternsTest {

    @Test
    void rowSweepsOneWholeRowPerFrame() {
        List<List<Position>> frames = Patterns.row().framesFor(3, 2);

        assertEquals(2, frames.size());
        assertEquals(Set.of(new Position(0, 0), new Position(0, 1), new Position(0, 2)), Set.copyOf(frames.get(0)));
        assertEquals(Set.of(new Position(1, 0), new Position(1, 1), new Position(1, 2)), Set.copyOf(frames.get(1)));
    }

    @Test
    void columnSweepsOneWholeColumnPerFrame() {
        List<List<Position>> frames = Patterns.column().framesFor(3, 2);

        assertEquals(3, frames.size());
        assertEquals(Set.of(new Position(0, 0), new Position(1, 0)), Set.copyOf(frames.get(0)));
        assertEquals(Set.of(new Position(0, 2), new Position(1, 2)), Set.copyOf(frames.get(2)));
    }

    @Test
    void mainDiagonalPartitionsTheWholeBoardExactlyOnce() {
        assertPartitionsBoard(Patterns.mainDiagonal(), 4, 3);
    }

    @Test
    void antiDiagonalPartitionsTheWholeBoardExactlyOnce() {
        assertPartitionsBoard(Patterns.antiDiagonal(), 4, 3);
    }

    @Test
    void rotatingChainsAllFourShapesWithoutDroppingAnyFrame() {
        int width = 4;
        int height = 3;
        int expectedFrameCount = Patterns.row().framesFor(width, height).size()
                + Patterns.column().framesFor(width, height).size()
                + Patterns.mainDiagonal().framesFor(width, height).size()
                + Patterns.antiDiagonal().framesFor(width, height).size();

        List<List<Position>> frames = Patterns.rotatingBuiltins(RandomSource.seeded(42)).framesFor(width, height);

        assertEquals(expectedFrameCount, frames.size());
        frames.forEach(frame -> assertFalse(frame.isEmpty()));
    }

    @Test
    void waveStaysWithinBoardBoundsForEveryColumn() {
        int width = 10;
        int height = 5;
        List<List<Position>> frames = Patterns.wave(1.5, 6, 2).framesFor(width, height);

        assertEquals(width, frames.size());
        for (List<Position> frame : frames) {
            assertFalse(frame.isEmpty());
            frame.forEach(position -> {
                assertTrue(position.row() >= 0 && position.row() < height);
                assertTrue(position.col() >= 0 && position.col() < width);
            });
        }
    }

    @Test
    void pathVisitsWaypointsInOrderOneFramePerWaypoint() {
        List<Position> waypoints = List.of(new Position(0, 0), new Position(0, 1), new Position(1, 1));

        List<List<Position>> frames = Patterns.path(waypoints).framesFor(2, 2);

        assertEquals(waypoints.stream().map(List::of).toList(), frames);
    }

    /** A sweep pattern is only useful for dodging if every board cell is lit at exactly one point in the sweep. */
    private void assertPartitionsBoard(MovementPattern pattern, int width, int height) {
        List<List<Position>> frames = pattern.framesFor(width, height);
        Set<Position> seenSoFar = new HashSet<>();

        for (List<Position> frame : frames) {
            assertFalse(frame.isEmpty());
            for (Position position : frame) {
                assertTrue(seenSoFar.add(position), "position " + position + " should be lit in exactly one frame");
            }
        }
        assertEquals(width * height, seenSoFar.size());
    }
}
