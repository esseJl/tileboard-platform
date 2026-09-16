package com.tileboard.app.gameengine.games.jump;

import com.tileboard.app.gameengine.Game;
import com.tileboard.app.gameengine.GameDefinition;
import com.tileboard.app.gameengine.GameFactory;
import com.tileboard.app.gameengine.GameMode;
import org.springframework.stereotype.Component;

/**
 * Registers {@link JumpGame} as {@code "jump"}, using {@link JumpPatterns#rotating()}
 * so a round moves through a random mix of row/column/diagonal sweeps rather
 * than always the same shape.
 *
 * <p>A variant that always uses a single fixed shape (e.g. a
 * {@code "jump-row"} game for a gentler, more predictable warm-up) is a
 * second {@code @Component} of exactly this shape, passing e.g.
 * {@link JumpPatterns#row()} instead - no change needed anywhere else.
 */
@Component
class JumpGameFactory implements GameFactory {

    private static final GameDefinition DEFINITION = new GameDefinition(
            "jump",
            "Jump",
            "A colored band sweeps across the board in a random row, column, or diagonal pattern - avoid touching it. "
                    + "Each touch on the band costs a life; survive the round to win.");

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
        return new JumpGame(DEFINITION, JumpPatterns.rotating(), JumpTuning.forMode(mode), width, height);
    }
}
