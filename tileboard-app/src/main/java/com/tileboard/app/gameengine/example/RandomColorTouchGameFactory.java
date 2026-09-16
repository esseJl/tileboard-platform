package com.tileboard.app.gameengine.example;

import com.tileboard.app.gameengine.Game;
import com.tileboard.app.gameengine.GameDefinition;
import com.tileboard.app.gameengine.GameFactory;
import com.tileboard.app.gameengine.GameMode;
import com.tileboard.gamekit.catalog.GameGenre;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Registers {@link RandomColorTouchGame}. Same shape as
 * {@link TouchEchoGameFactory} - proof that a colored (not just boolean)
 * game slots into the exact same extension point with zero changes
 * anywhere else in the platform.
 */
@Component
class RandomColorTouchGameFactory implements GameFactory {

    private static final GameDefinition DEFINITION = new GameDefinition(
            "random-color-touch",
            "Random Color Touch",
            "Every touched tile is repainted with a random color; untouched tiles keep their last color.",
            Set.of(GameGenre.ARCADE));

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
        return new RandomColorTouchGame(DEFINITION, width, height);
    }
}
