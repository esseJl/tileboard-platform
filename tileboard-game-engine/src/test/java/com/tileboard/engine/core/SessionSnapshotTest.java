package com.tileboard.engine.core;

import com.tileboard.engine.feature.HealthSystem;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SessionSnapshotTest {

    @Test
    void builderDefaultsEveryFeatureFieldToItsEmptyState() {
        SessionSnapshot snapshot = SessionSnapshot.builder().build();

        assertTrue(snapshot.scores().isEmpty());
        assertTrue(snapshot.board().isEmpty());
        assertNull(snapshot.remainingSeconds());
        assertNull(snapshot.countdownTotalSeconds());
        assertTrue(snapshot.health().isEmpty());
        assertTrue(snapshot.recentTouches().isEmpty());
        assertEquals(0L, snapshot.totalTouches());
        assertEquals(SessionSnapshot.ComboInfo.EMPTY, snapshot.combo());
        assertEquals(SessionSnapshot.ReactionInfo.EMPTY, snapshot.reaction());
        assertNull(snapshot.report(), "report must be null until the session actually finishes");
    }

    @Test
    void builderPopulatesEveryField() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        var touch = new SessionSnapshot.TouchInfo(1, 2, "TOUCH", now);
        var health = Map.of("p1", new HealthSystem.Status(2, 3));
        var report = new GameResult("s1", "g1", GameStatus.FINISHED, List.of(),
                Map.of("p1", 10), java.time.Duration.ofSeconds(30), now);

        SessionSnapshot snapshot = SessionSnapshot.builder()
                .scores(Map.of("p1", 10))
                .level(3)
                .status("RUNNING")
                .elapsedSeconds(42)
                .board(List.of(List.of("RED")))
                .remainingSeconds(15L)
                .countdownTotalSeconds(60L)
                .health(health)
                .recentTouches(List.of(touch))
                .totalTouches(7)
                .combo(new SessionSnapshot.ComboInfo(3, 5))
                .reaction(new SessionSnapshot.ReactionInfo(100.0, 80.0, 90.0, 4))
                .report(report)
                .build();

        assertEquals(10, snapshot.scores().get("p1"));
        assertEquals(3, snapshot.level());
        assertEquals("RUNNING", snapshot.status());
        assertEquals(42, snapshot.elapsedSeconds());
        assertEquals(List.of(List.of("RED")), snapshot.board());
        assertEquals(15L, snapshot.remainingSeconds());
        assertEquals(60L, snapshot.countdownTotalSeconds());
        assertEquals(new HealthSystem.Status(2, 3), snapshot.health().get("p1"));
        assertEquals(1, snapshot.recentTouches().size());
        assertEquals(touch, snapshot.recentTouches().get(0));
        assertEquals(7L, snapshot.totalTouches());
        assertEquals(3, snapshot.combo().current());
        assertEquals(5, snapshot.combo().max());
        assertEquals(100.0, snapshot.reaction().lastMs());
        assertSame(report, snapshot.report());
    }

    @Test
    void compactConstructorDefensivelyCopiesMutableCollections() {
        Map<String, Integer> scores = new HashMap<>(Map.of("p1", 1));
        List<List<String>> board = new ArrayList<>(List.of(new ArrayList<>(List.of("RED"))));
        Map<String, HealthSystem.Status> health = new HashMap<>(Map.of("p1", new HealthSystem.Status(1, 1)));
        List<SessionSnapshot.TouchInfo> touches = new ArrayList<>();

        SessionSnapshot snapshot = SessionSnapshot.builder()
                .scores(scores).board(board).health(health).recentTouches(touches)
                .build();

        // Mutating the caller's collections afterwards must not leak into the snapshot.
        scores.put("p1", 999);
        board.get(0).add("BLUE");
        health.put("p2", new HealthSystem.Status(1, 1));

        assertEquals(1, snapshot.scores().get("p1"));
        assertEquals(List.of("RED"), snapshot.board().get(0));
        assertEquals(1, snapshot.health().size());

        // And the snapshot's own exposed views must themselves be unmodifiable.
        assertThrows(UnsupportedOperationException.class, () -> snapshot.scores().put("x", 1));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.board().get(0).add("X"));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.health().put("x", new HealthSystem.Status(1, 1)));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.recentTouches().add(
                new SessionSnapshot.TouchInfo(0, 0, "TOUCH", Instant.now())));
    }

    @Test
    void legacyFourArgConstructorFillsFeatureFieldsWithEmptyDefaults() {
        SessionSnapshot snapshot = new SessionSnapshot(Map.of("p1", 1), 2, "RUNNING", 10);

        assertEquals(1, snapshot.scores().get("p1"));
        assertEquals(2, snapshot.level());
        assertEquals("RUNNING", snapshot.status());
        assertEquals(10, snapshot.elapsedSeconds());
        assertTrue(snapshot.board().isEmpty());
        assertNull(snapshot.remainingSeconds());
        assertNull(snapshot.report());
    }

    @Test
    void legacyFiveArgConstructorFillsFeatureFieldsWithEmptyDefaults() {
        SessionSnapshot snapshot = new SessionSnapshot(Map.of(), 1, "RUNNING", 0, List.of(List.of("OFF")));

        assertEquals(List.of(List.of("OFF")), snapshot.board());
        assertNull(snapshot.remainingSeconds());
        assertTrue(snapshot.health().isEmpty());
        assertEquals(SessionSnapshot.ComboInfo.EMPTY, snapshot.combo());
    }
}
