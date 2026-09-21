package com.tileboard.engine.feature;

import com.tileboard.engine.model.Player;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HealthSystemTest {
    Player p1 = Player.solo("A");
    Player p2 = Player.solo("B");

    @Test
    void damageReducesHealthAndReportsAliveState() {
        HealthSystem hs = new HealthSystem(List.of(p1), 3);
        assertTrue(hs.damage(p1.id()));
        assertEquals(2, hs.current(p1.id()));
        assertFalse(hs.damage(p1.id(), 5)); // clamp? damage returns false only when hp reaches 0
        assertEquals(0, hs.current(p1.id()));
        assertFalse(hs.isAlive(p1.id()));
    }

    @Test
    void healClampsToMax() {
        HealthSystem hs = new HealthSystem(List.of(p1), 3);
        hs.damage(p1.id(), 2);
        hs.heal(p1.id(), 10);
        assertEquals(3, hs.current(p1.id()));
    }

    @Test
    void allDeadTrueOnlyWhenEveryoneAtZero() {
        HealthSystem hs = new HealthSystem(List.of(p1, p2), 1);
        hs.damage(p1.id());
        assertFalse(hs.allDead());
        hs.damage(p2.id());
        assertTrue(hs.allDead());
    }
}
