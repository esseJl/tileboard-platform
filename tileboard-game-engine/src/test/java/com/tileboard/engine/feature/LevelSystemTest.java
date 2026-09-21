package com.tileboard.engine.feature;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LevelSystemTest {
    @Test
    void advanceIncreasesLevelAndSpeed() {
        LevelSystem ls = new LevelSystem();
        assertEquals(1, ls.currentLevel());
        ls.advance();
        assertEquals(2, ls.currentLevel());
        assertTrue(ls.currentSpeed() < 1000);
    }

    @Test
    void setLevelRejectsInvalid() {
        LevelSystem ls = new LevelSystem();
        assertThrows(IllegalArgumentException.class, () -> ls.setLevel(0));
    }
}
