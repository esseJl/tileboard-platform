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

    @Test
    void snapshotReflectsCurrentAndMaxIncludingLazyPlayers() {
        HealthSystem hs = new HealthSystem(List.of(p1, p2), 5);
        hs.damage(p1.id(), 2);
        hs.current("late"); // lazily creates "late" at the default max, like getOrCreate()

        var snapshot = hs.snapshot();

        assertEquals(new HealthSystem.Status(3, 5), snapshot.get(p1.id()));
        assertEquals(new HealthSystem.Status(5, 5), snapshot.get(p2.id()));
        assertEquals(new HealthSystem.Status(5, 5), snapshot.get("late"));
        assertEquals(3, snapshot.size());
    }

    @Test
    void snapshotIsUnmodifiableAndIndependentOfLaterChanges() {
        HealthSystem hs = new HealthSystem(List.of(p1), 5);
        var snapshot = hs.snapshot();

        hs.damage(p1.id(), 4);
        assertEquals(new HealthSystem.Status(5, 5), snapshot.get(p1.id()),
                "a previously taken snapshot must not change when health changes afterwards");
        assertThrows(UnsupportedOperationException.class, () -> snapshot.put("x", new HealthSystem.Status(1, 1)));
    }
}
