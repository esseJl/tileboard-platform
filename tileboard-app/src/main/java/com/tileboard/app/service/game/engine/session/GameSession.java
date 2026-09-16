package com.tileboard.app.service.game.engine.session;

import com.tileboard.app.service.game.engine.context.GameContext;
import com.tileboard.app.service.game.engine.core.Game;
import com.tileboard.app.service.game.engine.core.GameDefinition;
import com.tileboard.app.service.game.engine.core.GameMode;

import java.time.Instant;

/**
 * Runtime handle for the currently running game.
 */
public record GameSession(
        String gameId,
        GameDefinition definition,
        GameMode mode,
        Game<?> game,
        GameContext<?> context,
        Instant startedAt
) {
}
