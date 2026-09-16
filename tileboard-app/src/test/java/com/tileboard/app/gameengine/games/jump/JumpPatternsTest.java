package com.tileboard.app.gameengine.games.jump;

import com.tileboard.serial.board.Position;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class JumpPatternsTest {

    @Test
    void rowSweepsOneWholeRowPerFrame() {
        List<List<Position>> frames = JumpPatterns.row().framesFor(3, 2);

        assertThat(frames).hasSize(2);
        assertThat(frames.get(0)).containsExactlyInAnyOrder(new Position(0, 0), new Position(0, 1), new Position(0, 2));
        assertThat(frames.get(1)).containsExactlyInAnyOrder(new Position(1, 0), new Position(1, 1), new Position(1, 2));
    }

    @Test
    void columnSweepsOneWholeColumnPerFrame() {
        List<List<Position>> frames = JumpPatterns.column().framesFor(3, 2);

        assertThat(frames).hasSize(3);
        assertThat(frames.get(0)).containsExactlyInAnyOrder(new Position(0, 0), new Position(1, 0));
        assertThat(frames.get(2)).containsExactlyInAnyOrder(new Position(0, 2), new Position(1, 2));
    }

    @Test
    void mainDiagonalPartitionsTheWholeBoardExactlyOnce() {
        assertPartitionsBoard(JumpPatterns.mainDiagonal(), 4, 3);
    }

    @Test
    void antiDiagonalPartitionsTheWholeBoardExactlyOnce() {
        assertPartitionsBoard(JumpPatterns.antiDiagonal(), 4, 3);
    }

    @Test
    void rotatingChainsAllFourShapesWithoutDroppingAnyFrame() {
        int width = 4;
        int height = 3;
        int expectedFrameCount = JumpPatterns.row().framesFor(width, height).size()
                + JumpPatterns.column().framesFor(width, height).size()
                + JumpPatterns.mainDiagonal().framesFor(width, height).size()
                + JumpPatterns.antiDiagonal().framesFor(width, height).size();

        List<List<Position>> frames = JumpPatterns.rotating().framesFor(width, height);

        assertThat(frames).hasSize(expectedFrameCount);
        assertThat(frames).allSatisfy(frame -> assertThat(frame).isNotEmpty());
    }

    /** A sweep pattern is only useful for dodging if every board cell is lit at exactly one point in the sweep. */
    private void assertPartitionsBoard(JumpPattern pattern, int width, int height) {
        List<List<Position>> frames = pattern.framesFor(width, height);
        Set<Position> seenSoFar = new HashSet<>();

        for (List<Position> frame : frames) {
            assertThat(frame).isNotEmpty();
            for (Position position : frame) {
                assertThat(seenSoFar.add(position))
                        .as("position %s should be lit in exactly one frame", position)
                        .isTrue();
            }
        }
        assertThat(seenSoFar).hasSize(width * height);
    }
}
