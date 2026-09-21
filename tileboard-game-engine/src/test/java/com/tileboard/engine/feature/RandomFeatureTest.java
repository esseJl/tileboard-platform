package com.tileboard.engine.feature;

import com.tileboard.engine.model.TileColor;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.*;

class RandomFeatureTest {
    @Test
    void seededSequenceIsDeterministic() {
        RandomFeature a = new RandomFeature(5, 5, 42L);
        RandomFeature b = new RandomFeature(5, 5, 42L);
        assertEquals(a.randomPosition(), b.randomPosition());
    }

    @Test
    void randomColorNeverReturnsExcluded() {
        RandomFeature rf = new RandomFeature(5, 5, 1L);
        for (int i = 0; i < 100; i++) {
            assertNotEquals(TileColor.RED, rf.randomColor(TileColor.RED));
        }
    }

    @Test
    void randomColorThrowsWhenAllExcluded() {
        RandomFeature rf = new RandomFeature(5, 5, 1L);
        TileColor[] all = Arrays.stream(TileColor.values())
                .filter(c -> c != TileColor.OFF).toArray(TileColor[]::new);
        assertThrows(IllegalArgumentException.class, () -> rf.randomColor(all));
    }

    @Test
    void pickThrowsOnEmptyList() {
        RandomFeature rf = new RandomFeature(5, 5);
        assertThrows(NoSuchElementException.class, () -> rf.pick(List.of()));
    }
}
