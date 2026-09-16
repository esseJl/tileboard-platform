package com.tileboard.app.service.game.games.colormatch;

import com.tileboard.app.service.game.engine.color.TileColor;
import com.tileboard.app.service.game.engine.context.GameContext;
import com.tileboard.app.service.game.engine.core.*;
import com.tileboard.serial.board.Board;
import com.tileboard.serial.board.Position;
import com.tileboard.serial.board.TileCodec;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Colour-matching / target-colour game.
 * A target colour is announced (shown on a corner tile); the player must
 * step only on tiles of that colour. Built-in score, combo and level.
 */
public final class ColorMatchGame implements Game<TileColor> {

    private static final TileColor[] PALETTE = {
            TileColor.RED, TileColor.GREEN, TileColor.BLUE, TileColor.PINK, TileColor.CYAN
    };

    private final int targetsPerLevel;

    private GameContext<TileColor> ctx;
    private TileColor targetColor = TileColor.RED;
    private final List<Position> activeTargets = new ArrayList<>();

    public ColorMatchGame(GameMode mode) {
        this.targetsPerLevel = switch (mode) {
            case EASY -> 3;
            case NORMAL -> 5;
            case HARD -> 8;
        };
    }

    @Override
    public GameDefinition definition() {
        return DEFINITION;
    }

    @Override
    public TileCodec<TileColor> tileCodec() {
        return TileColor.CODEC;
    }

    @Override
    public void start(GameContext<TileColor> context) {
        this.ctx = context;
        ctx.score().fullReset();
        ctx.score().setMaxHealth(3);
        spawnRound();
    }

    private void spawnRound() {
        ctx.fill(TileColor.OFF);
        activeTargets.clear();
        targetColor = PALETTE[ThreadLocalRandom.current().nextInt(PALETTE.length)];

        // place target colour tiles
        for (int i = 0; i < targetsPerLevel + ctx.score().level() - 1; i++) {
            Position p = ctx.paths().randomPosition();
            ctx.setTile(p, targetColor);
            activeTargets.add(p);
        }
        // distractors
        for (int i = 0; i < targetsPerLevel; i++) {
            Position p = ctx.paths().randomPosition();
            TileColor distractor = PALETTE[ThreadLocalRandom.current().nextInt(PALETTE.length)];
            if (distractor != targetColor) {
                ctx.setTile(p, distractor);
            }
        }
        // show target colour on (0,0) as a hint
        ctx.setTile(0, 0, targetColor);
        ctx.publish();
    }

    @Override
    public void onPlayerInput(Board<Boolean> touchedTiles) {
        for (Position p : touchedTiles.positionsWhere(Boolean.TRUE::equals)) {
            if (p.row() == 0 && p.col() == 0) {
                continue; // ignore the hint tile
            }
            TileColor colour = ctx.board().get(p);
            if (colour == targetColor) {
                ctx.setTile(p, TileColor.WHITE);
                ctx.score().addScore(1);
                ctx.score().incrementCombo();
                activeTargets.remove(p);
                ctx.publish();
                if (activeTargets.isEmpty()) {
                    ctx.score().levelUp();
                    spawnRound();
                }
            } else if (colour != TileColor.OFF && colour != TileColor.WHITE) {
                ctx.score().resetCombo();
                ctx.score().damage(1);
                ctx.setTile(p, TileColor.OFF);
                ctx.publish();
                if (ctx.score().isGameOver()) {
                    ctx.lose("Wrong colour – out of health");
                }
            }
        }
    }

    static final GameDefinition DEFINITION = GameDefinition.builder("color-match")
            .displayName("Colour Match")
            .description("Step only on tiles matching the target colour shown in the corner.")
            .categories(GameCategory.ARCADE, GameCategory.EDUCATIONAL, GameCategory.PUZZLE)
            .build();

    @Component
    public static class Factory implements GameFactory {
        @Override public String gameId() { return DEFINITION.id(); }
        @Override public GameDefinition definition() { return DEFINITION; }
        @Override public Game<?> create(GameMode mode, int w, int h) { return new ColorMatchGame(mode); }
    }
}
