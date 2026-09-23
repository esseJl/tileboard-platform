package com.tileboard.engine.core;

import com.tileboard.engine.event.GameEvent;
import com.tileboard.engine.event.GameEventBusImpl;
import com.tileboard.engine.event.GameEventType;
import com.tileboard.engine.model.Player;
import com.tileboard.engine.model.TileEvent;
import com.tileboard.serial.board.Position;
import com.tileboard.serial.gateway.TileGatewayClient;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GameSessionImplTest {

    private GameDescriptor descriptor;
    private Game game;
    private TileGatewayClient gateway;
    private GameEventBusImpl bus;
    private ExecutorService teardown;

    @BeforeEach
    void setUp() {
        descriptor = new GameDescriptor("test-game", "Test", "TestGame", "desc", 3, 3, 1, 1);
        game = mock(Game.class);
        when(game.descriptor()).thenReturn(descriptor);
        gateway = mock(TileGatewayClient.class);
        bus = new GameEventBusImpl();
        teardown = Executors.newCachedThreadPool();
    }

    @AfterEach
    void tearDown() {
        bus.close();
        teardown.shutdownNow();
    }

    @Test
    void tickIsProcessedAfterStart() {
        GameSessionImpl session = new GameSessionImpl("s1", game, List.of(Player.solo("A")),
                gateway, Duration.ofMillis(10), bus, teardown, 100, null);
        session.start();

        verify(game, timeout(500).atLeastOnce()).onTick(session);
    }

    @Test
    void touchEventIsProcessedAfterStart() {
        GameSessionImpl session = new GameSessionImpl("s1", game, List.of(Player.solo("A")),
                gateway, null, bus, teardown, 100, null);
        session.start();

        session.handleTileEvent(TileEvent.touch(new Position(0, 0), "s1"));

        verify(game, timeout(500)).onTileEvent(eq(session), any(TileEvent.class));
    }

    @Test
    void stopFullyTerminatesSessionAndPublishesEvent() {
        List<GameEvent> events = Collections.synchronizedList(new ArrayList<>());
        bus.subscribeSession("s1", events::add);

        GameSessionImpl session = new GameSessionImpl("s1", game, List.of(Player.solo("A")),
                gateway, Duration.ofMillis(10), bus, teardown, 100, null);
        session.start();
        session.stop();

        assertEquals(GameStatus.STOPPED, session.status());
        verify(game, timeout(500)).onStop(eq(session), any(GameResult.class));

        Awaitility.await().atMost(Duration.ofSeconds(1)).untilAsserted(() ->
                assertTrue(events.stream().anyMatch(e -> e.type() == GameEventType.SESSION_STOPPED)));

        // report فقط باید در پیام پایانی (SESSION_STOPPED/SESSION_FINISHED) پر شده باشد،
        // نه در پیام‌های میانی (مثلاً SESSION_STARTED که قبل از آن منتشر شده)
        assertTrue(events.stream()
                        .filter(e -> e.type() != GameEventType.SESSION_STOPPED)
                        .allMatch(e -> e.payload().report() == null),
                "in-progress events must not carry a report yet");
        GameEvent stopped = events.stream()
                .filter(e -> e.type() == GameEventType.SESSION_STOPPED)
                .findFirst().orElseThrow();
        assertNotNull(stopped.payload().report(), "the terminal event must carry the full game report");
        assertEquals(GameStatus.STOPPED, stopped.payload().report().finalStatus());

        // بعد از توقف، تیک دیگر نباید صدا بخورد
        int callsAtStop = mockingDetails(game).getInvocations().size();
        try {
            Thread.sleep(60);
        } catch (InterruptedException ignored) {
        }
        assertEquals(callsAtStop, mockingDetails(game).getInvocations().size());
    }

    @Test
    void touchAndHealthFeaturesAreReflectedInPublishedSnapshots() {
        List<GameEvent> events = Collections.synchronizedList(new ArrayList<>());
        bus.subscribeSession("s1", events::add);

        Player player = Player.solo("A");
        GameSessionImpl session = new GameSessionImpl("s1", game, List.of(player),
                gateway, null, bus, teardown, 100, null);
        session.start();
        session.handleTileEvent(TileEvent.touch(new Position(1, 2), "s1"));
        verify(game, timeout(500)).onTileEvent(eq(session), any(TileEvent.class));

        // این تماس‌ها روی همان feature هایی عمل می‌کنند که snapshotForSse برای پر کردن
        // health/recentTouches/totalTouches از آن‌ها استفاده می‌کند.
        session.health().damage(player.id(), 1);

        // یک BOARD_UPDATED جدید منتشر می‌کنیم تا یک snapshot تازه (شامل تغییرات بالا) بگیریم.
        events.clear();
        session.setTile(0, 0, com.tileboard.engine.model.TileColor.RED);

        Awaitility.await().atMost(Duration.ofSeconds(1)).untilAsserted(() -> assertFalse(events.isEmpty()));
        SessionSnapshot latest = events.get(events.size() - 1).payload();

        assertEquals(1, latest.totalTouches());
        assertEquals(1, latest.recentTouches().size());
        assertEquals(1, latest.recentTouches().get(0).row());
        assertEquals(2, latest.recentTouches().get(0).col());
        assertEquals("TOUCH", latest.recentTouches().get(0).eventType());
        assertEquals(new com.tileboard.engine.feature.HealthSystem.Status(2, 3), latest.health().get(player.id()));
    }

    @Test
    void doubleStopIsIdempotentAndSafe() {
        GameSessionImpl session = new GameSessionImpl("s1", game, List.of(Player.solo("A")),
                gateway, null, bus, teardown, 100, null);
        session.start();
        session.stop();
        assertDoesNotThrow(session::stop);
        verify(game, times(1)).onStop(any(), any());
    }
}
