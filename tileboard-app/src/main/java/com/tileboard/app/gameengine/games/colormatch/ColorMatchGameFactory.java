package com.tileboard.app.gameengine.games.colormatch;

import com.tileboard.app.gameengine.Game;
import com.tileboard.app.gameengine.GameDefinition;
import com.tileboard.app.gameengine.GameFactory;
import com.tileboard.app.gameengine.GameMode;
import org.springframework.stereotype.Component;

/** Registers {@link ColorMatchGame} as {@code "color-match"}. */
@Component
class ColorMatchGameFactory implements GameFactory {

    private static final GameDefinition DEFINITION = new GameDefinition(
            "color-match",
            "Color Match",
            "Colored pairs are shown briefly, then hidden. Touch two tiles at a time to find every matching pair.");

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
        return new ColorMatchGame(DEFINITION, ColorMatchTuning.forMode(mode, width * height), width, height);
    }
}
