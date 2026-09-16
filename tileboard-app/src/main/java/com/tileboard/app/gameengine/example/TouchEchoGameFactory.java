package com.tileboard.app.gameengine.example;

import com.tileboard.app.gameengine.Game;
import com.tileboard.app.gameengine.GameDefinition;
import com.tileboard.app.gameengine.GameFactory;
import com.tileboard.app.gameengine.GameMode;
import org.springframework.stereotype.Component;

/**
 * Registers {@link TouchEchoGame} with the platform. This class is the
 * entire integration surface a game needs: implement {@link GameFactory},
 * annotate it as a Spring component, and it shows up in {@code GET /api/v1/games}
 * and becomes startable automatically - {@link com.tileboard.app.gameengine.GameRegistry}
 * picks it up with no other code changes.
 *
 * <p>Future real games (matching pairs, reaction-time, snake-and-ladders,
 * ...) follow exactly this same shape.
 */
@Component
class TouchEchoGameFactory implements GameFactory {

    private static final GameDefinition DEFINITION = new GameDefinition(
            "touch-echo",
            "Touch Echo",
            "Reference example: lights up whichever tile is touched. Demonstrates the game extension point end to end.");

    @Override
    public String gameId() {
        return DEFINITION.id();
    }

    @Override
    public GameDefinition definition() {
        return DEFINITION;
    }

    @Override
    public Game<?> create(GameMode mode, int width, int height) {
        return new TouchEchoGame(DEFINITION, width, height);
    }
}
