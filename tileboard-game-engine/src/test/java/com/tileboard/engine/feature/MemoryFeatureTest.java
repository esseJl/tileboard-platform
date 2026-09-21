package com.tileboard.engine.feature;

import com.tileboard.serial.board.Position;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryFeatureTest {
    @Test
    void correctSequenceIsFullyCorrect() {
        MemoryFeature mem = new MemoryFeature();
        List<Position> target = List.of(new Position(0, 0), new Position(0, 1));
        mem.setTarget(target);
        mem.addInput(new Position(0, 0));
        assertTrue(mem.isCorrectSoFar());
        mem.addInput(new Position(0, 1));
        assertTrue(mem.isFullyCorrect());
        assertTrue(mem.isComplete());
    }

    @Test
    void wrongInputBreaksCorrectness() {
        MemoryFeature mem = new MemoryFeature();
        mem.setTarget(List.of(new Position(0, 0)));
        mem.addInput(new Position(1, 1));
        assertFalse(mem.isCorrectSoFar());
    }
}
