package com.tileboard.gamekit.state;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TrackersTest {
    @Test
    void healthScoreAndComboAreBoundedAndThreadSafeByContract() {
        HealthTracker health = new HealthTracker(3);
        assertEquals(3, health.current());
        assertEquals(2, health.damage(2));
        assertEquals(0, health.damage(5));
        assertTrue(health.isDepleted());
        assertEquals(3, health.heal(10));

        ScoreBoard scores = new ScoreBoard();
        assertEquals(100, scores.add("p1", 100));
        assertEquals(90, scores.add("p1", -10));
        assertEquals(90, scores.get("p1"));

        ComboTracker combo = new ComboTracker(2);
        assertEquals(1, combo.hit());
        assertEquals(2, combo.hit());
        combo.miss();
        assertEquals(2, combo.current());
        combo.miss();
        assertEquals(0, combo.current());
    }
}
