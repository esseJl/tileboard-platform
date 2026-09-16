package com.tileboard.app.service.game.games.snakes;

import com.tileboard.app.service.game.engine.color.TileColor;
import com.tileboard.app.service.game.engine.context.GameContext;
import com.tileboard.app.service.game.engine.core.*;
import com.tileboard.serial.board.Board;
import com.tileboard.serial.board.Position;
import com.tileboard.serial.board.TileCodec;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Simplified Snakes & Ladders for a tile board.
 * Players take turns "rolling" by stepping any free tile; the piece advances
 * and may hit a snake (back) or ladder (forward). Two-player competitive.
 */
public final class SnakesAndLaddersGame implements Game<TileColor> {

    private GameContext<TileColor> ctx;
    private final Map<Integer, Integer> snakes = new HashMap<>();
    private final Map<Integer, Integer> ladders = new HashMap<>();
    private int p1 = 0;
    private int p2 = 0;
    private boolean p1Turn = true;
    private boolean finished;

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
        this.finished = false;
        this.p1 = 0;
        this.p2 = 0;
        this.p1Turn = true;
        buildBoardLinks();
        redraw();
    }

    private void buildBoardLinks() {
        snakes.clear();
        ladders.clear();
        int cells = ctx.width() * ctx.height();
        for (int i = 0; i < 3; i++) {
            int from = ThreadLocalRandom.current().nextInt(1, Math.max(2, cells / 2));
            int to = ThreadLocalRandom.current().nextInt(from + 1, cells);
            ladders.put(from, to);
        }
        for (int i = 0; i < 3; i++) {
            int from = ThreadLocalRandom.current().nextInt(Math.max(1, cells / 2), Math.max(2, cells - 1));
            int to = ThreadLocalRandom.current().nextInt(0, from);
            snakes.put(from, to);
        }
    }

    private Position positionOf(int index) {
        return new Position(index / ctx.width(), index % ctx.width());
    }

    private void redraw() {
        ctx.fill(TileColor.OFF);
        for (int from : ladders.keySet()) {
            ctx.setTile(positionOf(from), TileColor.GREEN);
        }
        for (int from : snakes.keySet()) {
            ctx.setTile(positionOf(from), TileColor.RED);
        }
        int max = ctx.width() * ctx.height();
        if (p1 < max) {
            ctx.setTile(positionOf(p1), TileColor.BLUE);
        }
        if (p2 < max) {
            ctx.setTile(positionOf(p2), TileColor.PINK);
        }
        ctx.publish();
    }

    @Override
    public void onPlayerInput(Board<Boolean> touchedTiles) {
        if (finished) {
            return;
        }
        if (touchedTiles.positionsWhere(Boolean.TRUE::equals).isEmpty()) {
            return;
        }
        int roll = ThreadLocalRandom.current().nextInt(1, 7);
        if (p1Turn) {
            p1 = advance(p1, roll);
            if (p1 >= ctx.width() * ctx.height() - 1) {
                finished = true;
                ctx.win("Player 1 (BLUE) wins");
            }
        } else {
            p2 = advance(p2, roll);
            if (p2 >= ctx.width() * ctx.height() - 1) {
                finished = true;
                ctx.win("Player 2 (PINK) wins");
            }
        }
        p1Turn = !p1Turn;
        redraw();
    }

    private int advance(int pos, int roll) {
        int next = Math.min(pos + roll, ctx.width() * ctx.height() - 1);
        if (ladders.containsKey(next)) {
            next = ladders.get(next);
        } else if (snakes.containsKey(next)) {
            next = snakes.get(next);
        }
        return next;
    }

    static final GameDefinition DEFINITION = GameDefinition.builder("snakes-ladders")
            .displayName("Snakes & Ladders")
            .description("Two-player race with random snakes and ladders.")
            .categories(GameCategory.PUZZLE, GameCategory.COMPETITIVE, GameCategory.GROUP)
            .supportsTwoPlayer(true)
            .build();

    @Component
    public static class Factory implements GameFactory {
        @Override public String gameId() { return DEFINITION.id(); }
        @Override public GameDefinition definition() { return DEFINITION; }
        @Override public Game<?> create(GameMode mode, int w, int h) { return new SnakesAndLaddersGame(); }
    }
}
