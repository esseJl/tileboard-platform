package com.tileboard.engine.core;


import com.tileboard.serial.board.Board;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TouchFrameRouterTest {

    @Test
    void routesOnlyToTheExclusiveOwner() {
        GameSessionImpl owner = mock(GameSessionImpl.class);
        when(owner.boardWidth()).thenReturn(2);
        when(owner.boardHeight()).thenReturn(2);
        when(owner.sessionId()).thenReturn("owner");
        GameSessionImpl other = mock(GameSessionImpl.class);

        TouchFrameRouter router = new TouchFrameRouter(
                id -> "owner".equals(id) ? Optional.of(owner) : Optional.of(other),
                () -> Optional.of("owner"));

        Board<Boolean> touches = new Board<>(2, 2, false);
        touches.set(0, 1, true);
        router.route(touches);

        verify(owner, times(1)).handleTileEvent(any());
        verifyNoInteractions(other);
    }

    @Test
    void doesNothingWhenNoSessionOwnsTheBoard() {
        GameSessionImpl other = mock(GameSessionImpl.class);
        TouchFrameRouter router = new TouchFrameRouter(id -> Optional.of(other), Optional::empty);

        router.route(new Board<>(1, 1, true));

        verifyNoInteractions(other);
    }
}
