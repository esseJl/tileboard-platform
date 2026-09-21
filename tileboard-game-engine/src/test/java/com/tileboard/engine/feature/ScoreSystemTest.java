package com.tileboard.engine.feature;

import com.tileboard.engine.model.Player;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ScoreSystemTest {
    @Test
    void addSubtractAndLeader() {
        Player a = Player.solo("A");
        Player b = Player.solo("B");
        ScoreSystem scores = new ScoreSystem(List.of(a, b));
        scores.add(a.id(), 10);
        scores.add(b.id(), 5);
        assertEquals(10, scores.get(a.id()));
        assertEquals(Optional.of(a.id()), scores.leader());
        scores.subtract(a.id(), 20);
        assertEquals(-10, scores.get(a.id()));
    }
}