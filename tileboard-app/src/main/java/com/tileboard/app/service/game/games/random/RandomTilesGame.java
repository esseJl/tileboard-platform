package com.tileboard.app.service.game.games.random;

import com.tileboard.app.service.game.engine.color.TileColor;
import com.tileboard.app.service.game.engine.context.GameContext;
import com.tileboard.app.service.game.engine.core.*;
import com.tileboard.serial.board.Board;
import com.tileboard.serial.board.Position;
import com.tileboard.serial.board.TileCodec;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Simple reaction / random-target game: a random tile lights up; step on it
 * before it disappears to score. Uses timer, score, combo and reaction-speed metrics.
 */
public final class RandomTilesGame implements Game<TileColor> {

    private final int lifeMs;

    private GameContext<TileColor> ctx;
    private Position current;
    private long appearedAtNanos;
    private ScheduledExecutorService scheduler;

    public RandomTilesGame(GameMode mode) {
        this.lifeMs = switch (mode) {
            case EASY -> 1500;
            case NORMAL -> 900;
            case HARD -> 500;
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
        ctx.score().setMaxHealth(5);
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "random-tiles");
            t.setDaemon(true);
            return t;
        });
        spawn();
    }

    private void spawn() {
        if (ctx == null || !ctx.isActive()) {
            return;
        }
        ctx.fill(TileColor.OFF);
        current = ctx.paths().randomPosition();
        ctx.setTile(current, TileColor.GREEN);
        ctx.publish();
        appearedAtNanos = System.nanoTime();
        scheduler.schedule(this::timeout, lifeMs, TimeUnit.MILLISECONDS);
    }

    private void timeout() {
        if (current == null || !ctx.isActive()) {
            return;
        }
        ctx.score().damage(1);
        ctx.score().resetCombo();
        current = null;
        if (ctx.score().isGameOver()) {
            ctx.lose("Too slow – out of health");
        } else {
            spawn();
        }
    }

    @Override
    public void onPlayerInput(Board<Boolean> touchedTiles) {
        if (current == null) {
            return;
        }
        for (Position p : touchedTiles.positionsWhere(Boolean.TRUE::equals)) {
            if (p.equals(current)) {
                long reactionMs = (System.nanoTime() - appearedAtNanos) / 1_000_000L;
                int points = Math.max(1, (int) ((lifeMs - reactionMs) / 100));
                ctx.score().addScore(points);
                ctx.score().incrementCombo();
                current = null;
                ctx.setTile(p, TileColor.WHITE);
                ctx.publish();
                scheduler.schedule(this::spawn, 200, TimeUnit.MILLISECONDS);
                return;
            }
        }
        // wrong tile
        if (!touchedTiles.positionsWhere(Boolean.TRUE::equals).isEmpty()) {
            ctx.score().resetCombo();
            ctx.score().damage(1);
            if (ctx.score().isGameOver()) {
                ctx.lose("Wrong tile – out of health");
            }
        }
    }

    @Override
    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        if (ctx != null) {
            ctx.deactivate();
        }
    }

    static final GameDefinition DEFINITION = GameDefinition.builder("random-tiles")
            .displayName("Random Tiles")
            .description("React and step on the lit tile before it disappears.")
            .categories(GameCategory.ARCADE, GameCategory.EDUCATIONAL_MOTOR)
            .build();

    @Component
    public static class Factory implements GameFactory {
        @Override public String gameId() { return DEFINITION.id(); }
        @Override public GameDefinition definition() { return DEFINITION; }
        @Override public Game<?> create(GameMode mode, int w, int h) { return new RandomTilesGame(mode); }
    }
}
