package com.tileboard.engine.feature;

import com.tileboard.engine.model.Player;
import com.tileboard.engine.model.PlayerRole;
import com.tileboard.engine.model.TileColor;
import com.tileboard.engine.model.TileEvent;
import com.tileboard.serial.board.Board;
import com.tileboard.serial.board.Position;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class FeatureSystemsTest {

    private static final Player P1 = new Player("p1", "Alice", PlayerRole.PLAYER_ONE);
    private static final Player P2 = new Player("p2", "Bob", PlayerRole.PLAYER_ONE);

    @Test
    void scoreSystemSupportsMutationSnapshotsLeaderAndConcurrency() throws Exception {
        ScoreSystem scores = new ScoreSystem(List.of(P1, P2));
        assertEquals(0, scores.get("p1"));
        assertEquals(5, scores.add("p1", 5));
        assertEquals(3, scores.subtract("p1", 2));
        scores.set("p2", 10);
        assertEquals("p2", scores.leader().orElseThrow());
        assertThrows(UnsupportedOperationException.class, () -> scores.allScores().put("x", 1));

        int threads = 8, increments = 1_000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                start.await();
                for (int i = 0; i < increments; i++) scores.add("concurrent", 1);
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        assertEquals(threads * increments, scores.get("concurrent"));
        scores.reset("p1");
        assertEquals(0, scores.get("p1"));
        scores.resetAll();
        assertTrue(scores.allScores().values().stream().allMatch(v -> v == 0));
    }

    @Test
    void healthSystemClampsDamageHealAndLazyPlayersToConfiguredMax() {
        HealthSystem health = new HealthSystem(List.of(P1), 5);
        assertEquals(5, health.current("p1"));
        assertEquals(5, health.max("late"));
        assertEquals(5, health.current("late"));
        assertTrue(health.damage("p1", 2));
        assertEquals(3, health.current("p1"));
        health.heal("p1", 100);
        assertEquals(5, health.current("p1"));
        assertFalse(health.damage("p1", 99));
        assertEquals(0, health.current("p1"));
        health.damage("late", 99);
        assertTrue(health.allDead());
        health.resetAll(99);
        assertEquals(5, health.current("p1"));
        assertEquals(5, health.current("late"));
    }

    @Test
    void levelSystemValidatesAndSupportsCustomScaling() {
        LevelSystem levels = new LevelSystem();
        assertEquals(1, levels.currentLevel());
        assertEquals(2, levels.advance());
        levels.setLevel(4);
        assertEquals(700, levels.currentSpeed());
        levels.setSpeedScaler(lvl -> lvl * 10);
        assertEquals(40, levels.currentSpeed());
        assertThrows(IllegalArgumentException.class, () -> levels.setLevel(0));
        levels.reset();
        assertEquals(1, levels.currentLevel());
    }

    @Test
    void comboTrackerTracksMaxMultiplierAndReset() {
        ComboTracker combo = new ComboTracker();
        combo.setComboTimeout(60_000);
        assertEquals(1, combo.hit());
        assertEquals(2, combo.hit());
        assertEquals(2, combo.max());
        assertEquals(2, combo.multiplier(2));
        combo.reset();
        assertEquals(0, combo.current());
        assertEquals(2, combo.max());
    }

    @Test
    void gameTimerHandlesLifecycleAndFiresExpiryExactlyOnce() throws Exception {
        GameTimer timer = new GameTimer();
        assertEquals(Duration.ZERO, timer.elapsed());
        assertEquals(Duration.ZERO, timer.remaining());
        AtomicInteger callbacks = new AtomicInteger();
        timer.startCountdown(Duration.ofMillis(15), callbacks::incrementAndGet);
        Thread.sleep(25);
        assertTrue(timer.isExpired());
        timer.checkExpiry();
        timer.checkExpiry();
        assertEquals(1, callbacks.get());
        timer.reset();
        assertFalse(timer.isExpired());
        timer.start();
        Thread.sleep(2);
        timer.stop();
        Duration stopped = timer.elapsed();
        Thread.sleep(2);
        assertEquals(stopped, timer.elapsed());
    }

    @Test
    void touchHistoryAndAnalyzerExposeStableSnapshotsAndSequenceQueries() {
        TouchHistory history = new TouchHistory("s1");
        TouchAnalyzer analyzer = new TouchAnalyzer(history);
        Position a = new Position(0, 0), b = new Position(0, 1);
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        history.record(new TileEvent(a, com.tileboard.engine.model.TileEventType.TOUCH, t0, "s1"));
        history.record(new TileEvent(b, com.tileboard.engine.model.TileEventType.TOUCH, t0.plusMillis(100), "s1"));
        assertEquals(2, history.totalTouches());
        assertEquals(b, history.lastTouchedPosition().orElseThrow());
        assertEquals(List.of(a, b), history.positionOrder());
        assertEquals(Set.of(a, b), history.distinctPositions());
        assertTrue(analyzer.matchesSequence(List.of(a, b)));
        assertTrue(analyzer.allUnique());
        assertEquals(Duration.ofMillis(100), analyzer.averageInterTouchGap());
        assertEquals(Duration.ofMillis(100), analyzer.lastReactionTime().orElseThrow());
        history.record(new TileEvent(a, com.tileboard.engine.model.TileEventType.TOUCH, t0.plusMillis(150), "s1"));
        assertFalse(analyzer.allUnique());
        history.reset();
        assertEquals(0, history.totalTouches());
    }

    @Test
    void boardFeatureCoversPredicatesBoundsAndDistances() {
        Board<TileColor> board = new Board<>(3, 2, TileColor.OFF);
        board.set(0, 0, TileColor.RED);
        board.set(1, 2, TileColor.RED);
        BoardFeature feature = new BoardFeature(3, 2);
        assertEquals(2, feature.countByColor(board, TileColor.RED));
        assertEquals(2, feature.findByColor(board, TileColor.RED).size());
        assertFalse(feature.allMatch(board, TileColor.RED::equals));
        assertTrue(feature.noneMatch(board, TileColor.BLUE::equals));
        assertTrue(feature.isValid(new Position(1, 2)));
        assertFalse(feature.isValid(new Position(2, 2)));
        assertEquals(3, feature.manhattanDistance(new Position(0, 0), new Position(1, 2)));
        assertEquals(2, feature.chebyshevDistance(new Position(0, 0), new Position(1, 2)));
    }

    @Test
    void patternMatcherHandlesTailExactContainsAndCyclicCases() {
        PatternMatcher matcher = new PatternMatcher();
        Position a = new Position(0, 0), b = new Position(0, 1), c = new Position(0, 2);
        assertTrue(matcher.tailMatches(List.of(c, a, b), List.of(a, b)));
        assertFalse(matcher.tailMatches(List.of(a), List.of(a, b)));
        assertTrue(matcher.exactMatch(List.of(a, b), List.of(a, b)));
        assertTrue(matcher.containsSequence(List.of(c, a, b, c), List.of(a, b)));
        assertTrue(matcher.cyclicMatch(List.of(b, c, a), List.of(a, b, c)));
        assertTrue(matcher.tailMatches(List.of(a), List.of()));
    }

    @Test
    void randomFeatureIsReproducibleBoundedAndHonorsExclusions() {
        RandomFeature a = new RandomFeature(4, 3, 42L);
        RandomFeature b = new RandomFeature(4, 3, 42L);
        assertEquals(a.randomPosition(), b.randomPosition());
        List<Position> positions = a.randomPositions(100);
        assertEquals(12, positions.size());
        assertEquals(12, positions.stream().distinct().count());
        assertTrue(positions.stream().allMatch(p -> p.row() >= 0 && p.row() < 3 && p.col() >= 0 && p.col() < 4));
        assertNotEquals(TileColor.OFF, a.randomColor(TileColor.RED));
        assertThrows(java.util.NoSuchElementException.class, () -> a.pick(List.of()));
        assertFalse(a.chance(0.0));
        assertTrue(a.chance(1.0));
        a.reseed(7L);
        b.reseed(7L);
        assertEquals(a.randomPosition(), b.randomPosition());
    }

    @Test
    void memoryFeatureTracksPrefixCompletionAndOverInputSafely() {
        MemoryFeature memory = new MemoryFeature();
        Position a = new Position(0, 0), b = new Position(1, 1), x = new Position(2, 2);
        memory.setTarget(List.of(a, b));
        assertEquals(2, memory.targetLength());
        assertTrue(memory.isCorrectSoFar());
        memory.addInput(a);
        assertTrue(memory.isCorrectSoFar());
        assertFalse(memory.isComplete());
        memory.addInput(b);
        assertTrue(memory.isComplete());
        assertTrue(memory.isFullyCorrect());
        memory.addInput(x);
        assertFalse(memory.isCorrectSoFar());
        assertFalse(memory.isFullyCorrect());
        memory.resetInput();
        assertEquals(0, memory.inputLength());
    }

    @Test
    void graphFeatureFindsShortestPathAndConnectedComponents() {
        GraphFeature graph = new GraphFeature(3, 3);
        Position from = new Position(0, 0), to = new Position(2, 2), blocked = new Position(1, 1);
        List<Position> path = graph.shortestPath(from, to, p -> !p.equals(blocked));
        assertFalse(path.isEmpty());
        assertEquals(from, path.get(0));
        assertEquals(to, path.get(path.size() - 1));
        assertEquals(5, path.size());
        assertTrue(graph.shortestPath(from, to, p -> false).isEmpty());
        assertEquals(1, graph.connectedComponents(p -> !p.equals(blocked)).size());
        assertEquals(2, graph.connectedComponents(p -> p.equals(new Position(0, 0)) || p.equals(new Position(2, 2))).size());
    }
}
