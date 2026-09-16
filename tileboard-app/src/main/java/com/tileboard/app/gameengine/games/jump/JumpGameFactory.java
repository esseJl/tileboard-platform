package com.tileboard.app.gameengine.games.jump;

import com.tileboard.app.gameengine.Game;
import com.tileboard.app.gameengine.GameDefinition;
import com.tileboard.app.gameengine.GameFactory;
import com.tileboard.app.gameengine.GameMode;
import com.tileboard.gamekit.catalog.GameGenre;
import com.tileboard.gamekit.pattern.Patterns;
import com.tileboard.gamekit.time.RandomSource;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Registers {@link JumpGame} as {@code "jump"}, using
 * {@link Patterns#rotatingBuiltins} so a round moves through a random mix
 * of row/column/diagonal sweeps rather than always the same shape.
 *
 * <p>A variant that always uses a single fixed shape (e.g. a
 * {@code "jump-row"} game for a gentler, more predictable warm-up), or one
 * that sweeps a {@link Patterns#wave wave} instead, is a second
 * {@code @Component} of exactly this shape, passing a different
 * {@code MovementPattern} - no change needed anywhere else.
 */
@Component
class JumpGameFactory implements GameFactory {

    private static final GameDefinition DEFINITION = new GameDefinition(
            "jump",
            "Jump",
            "A colored band sweeps across the board in a random row, column, or diagonal pattern - avoid touching it. "
                    + "Each touch on the band costs a life; survive the round to win.",
            Set.of(GameGenre.ARCADE, GameGenre.EDUCATIONAL_MOTOR));

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
        return new JumpGame(DEFINITION, Patterns.rotatingBuiltins(RandomSource.threadLocal()), JumpTuning.forMode(mode), width, height);
    }
}
