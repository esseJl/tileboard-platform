package com.tileboard.engine.core;

import com.tileboard.engine.event.GameEventBus;
import com.tileboard.engine.exception.GameSessionException;
import com.tileboard.engine.model.Player;
import com.tileboard.serial.gateway.TileGatewayClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GameSessionImplStartFailureTest {

    @Mock
    TileGatewayClient gateway;
    @Mock
    GameEventBus eventBus;

    @Test
    void onStartFailureIsPropagatedAndSessionEndsUpStopped() {
        Game failingGame = mock(Game.class);
        GameDescriptor descriptor = GameDescriptor.builder("boom", "Boom").boardSize(2, 2).build();
        when(failingGame.descriptor()).thenReturn(descriptor);
        doThrow(new IllegalStateException("kaboom")).when(failingGame).onStart(any());

        GameSessionImpl session = new GameSessionImpl(
                "s1", failingGame, List.of(Player.solo("Alice")), gateway, null, eventBus);

        GameSessionException ex = assertThrows(GameSessionException.class, session::start);
        assertTrue(ex.getCause() instanceof IllegalStateException);
        assertEquals(GameStatus.STOPPED, session.status());
        assertTrue(session.result().isPresent());
    }
}
