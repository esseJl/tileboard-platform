package com.tileboard.engine.core;

import com.tileboard.engine.exception.GameNotFoundException;
import com.tileboard.engine.model.Player;
import com.tileboard.engine.model.PlayerRole;
import com.tileboard.engine.model.TouchSequence;
import com.tileboard.serial.board.Position;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CoreRegistryAndModelTest {

    @Test
    void descriptorBuilderAndValidationAreCorrect() {
        GameDescriptor d = GameDescriptor.builder("g", "Game")
                .category("ARCADE").description("desc").boardSize(4,5).players(2,4).build();
        assertEquals("g", d.gameId());
        assertEquals(4, d.requiredWidth());
        assertEquals(5, d.requiredHeight());
        assertEquals(2, d.minPlayers());
        assertEquals(4, d.maxPlayers());
        assertThrows(IllegalArgumentException.class, () -> GameDescriptor.builder("x","x").boardSize(0,1).build());
        assertThrows(IllegalArgumentException.class, () -> GameDescriptor.builder("x","x").players(2,1).build());
    }

    @Test
    void registryRegistersSortsInstantiatesAndRejectsUnknownGame() {
        DefaultGameRegistry registry = new DefaultGameRegistry();
        AtomicInteger created = new AtomicInteger();
        GameDescriptor z = GameDescriptor.builder("z", "Zulu").build();
        GameDescriptor a = GameDescriptor.builder("a", "Alpha").build();
        registry.register(z, () -> { created.incrementAndGet(); return stubGame(z); });
        registry.register(a, () -> stubGame(a));
        assertTrue(registry.isRegistered("z"));
        assertEquals(List.of("Alpha", "Zulu"), registry.listAll().stream().map(GameDescriptor::displayName).toList());
        assertEquals(z, registry.find("z").orElseThrow());
        assertEquals(z, registry.instantiate("z").descriptor());
        assertEquals(1, created.get());
        assertThrows(GameNotFoundException.class, () -> registry.instantiate("missing"));
    }

    @Test
    void touchSequenceIsImmutableAndCalculatesDurations() {
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        TouchSequence seq = new TouchSequence(
                List.of(new Position(0,0), new Position(0,1), new Position(0,2)),
                List.of(t0, t0.plusMillis(100), t0.plusMillis(300)));
        assertEquals(3, seq.size());
        assertEquals(Duration.ofMillis(100), seq.gapBetween(0));
        assertEquals(List.of(Duration.ofMillis(100), Duration.ofMillis(200)), seq.gaps());
        assertEquals(Duration.ofMillis(150), seq.averageGap());
        assertThrows(UnsupportedOperationException.class, () -> seq.positions().add(new Position(1,1)));
        assertThrows(IllegalArgumentException.class, () -> new TouchSequence(List.of(new Position(0,0)), List.of()));
        assertTrue(TouchSequence.empty().isEmpty());
    }

    @Test
    void playerFactoriesGenerateValidPlayers() {
        Player solo = Player.solo("Alice");
        assertEquals("Alice", solo.name());
        assertEquals(PlayerRole.SOLO, solo.role());
        assertNotNull(solo.id());
        assertFalse(solo.id().isBlank());
    }

    private static Game stubGame(GameDescriptor descriptor) {
        return new Game() {
            @Override public GameDescriptor descriptor() { return descriptor; }
            @Override public void onStart(GameContext context) { }
            @Override public void onTick(GameContext context) { }
            @Override public void onTileEvent(GameContext context, com.tileboard.engine.model.TileEvent event) { }
            @Override public void onStop(GameContext context, GameResult result) { }
        };
    }
}
