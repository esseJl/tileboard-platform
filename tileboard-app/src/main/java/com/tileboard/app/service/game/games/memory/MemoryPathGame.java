package com.tileboard.app.service.game.games.memory;

import com.tileboard.app.service.game.engine.color.TileColor;
import com.tileboard.app.service.game.engine.context.GameContext;
import com.tileboard.app.service.game.engine.core.*;
import com.tileboard.app.service.game.engine.event.GameEventListener;
import com.tileboard.serial.board.Board;
import com.tileboard.serial.board.Position;
import com.tileboard.serial.board.TileCodec;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Classic path-memory game: the board shows a path, then the player must
 * reproduce it in order. Uses built-in PathGenerator, TouchHistory and Score.
 */
public final class MemoryPathGame implements Game<TileColor> {

    private enum Phase { SHOW, INPUT, RESULT }

    private final int pathLength;
    private final int showIntervalMs;

    private GameContext<TileColor> ctx;
    private List<Position> path = List.of();
    private final AtomicInteger showIndex = new AtomicInteger(0);
    private int inputIndex;
    private Phase phase = Phase.SHOW;
    private ScheduledExecutorService scheduler;

    public MemoryPathGame(GameMode mode) {
        this.pathLength = switch (mode) {
            case EASY -> 3;
            case NORMAL -> 5;
            case HARD -> 7;
        };
        this.showIntervalMs = switch (mode) {
            case EASY -> 700;
            case NORMAL -> 500;
            case HARD -> 350;
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
        nextRound();
    }

    private void nextRound() {
        path = ctx.paths().randomOrthogonalPath(pathLength + ctx.score().level() - 1);
        showIndex.set(0);
        inputIndex = 0;
        phase = Phase.SHOW;
        ctx.fill(TileColor.OFF);
        ctx.publish();

        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "memory-path");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::showTick, 300, showIntervalMs, TimeUnit.MILLISECONDS);
    }

    private void showTick() {
        if (phase != Phase.SHOW || !ctx.isActive()) {
            return;
        }
        int idx = showIndex.getAndIncrement();
        if (idx >= path.size()) {
            phase = Phase.INPUT;
            ctx.fill(TileColor.OFF);
            ctx.publish();
            scheduler.shutdownNow();
            return;
        }
        ctx.fill(TileColor.OFF);
        for (int i = 0; i <= idx; i++) {
            ctx.setTile(path.get(i), TileColor.GREEN);
        }
        ctx.publish();
    }

    @Override
    public void onPlayerInput(Board<Boolean> touchedTiles) {
        if (phase != Phase.INPUT) {
            return;
        }
        List<Position> pressed = touchedTiles.positionsWhere(Boolean.TRUE::equals);
        if (pressed.isEmpty()) {
            return;
        }
        Position expected = path.get(inputIndex);
        Position actual = pressed.get(0);

        if (actual.equals(expected)) {
            ctx.setTile(actual, TileColor.GREEN);
            ctx.publish();
            inputIndex++;
            ctx.score().incrementCombo();
            if (inputIndex >= path.size()) {
                phase = Phase.RESULT;
                ctx.score().addScore(path.size());
                ctx.score().levelUp();
                ctx.fire(GameEventListener.GameEvent.levelUp(ctx.score().level()));
                // brief success flash then next round
                ctx.fill(TileColor.WHITE);
                ctx.publish();
                scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "memory-next");
                    t.setDaemon(true);
                    return t;
                });
                scheduler.schedule(this::nextRound, 800, TimeUnit.MILLISECONDS);
            }
        } else {
            phase = Phase.RESULT;
            ctx.score().resetCombo();
            ctx.score().damage(1);
            ctx.fill(TileColor.RED);
            ctx.publish();
            if (ctx.score().isGameOver()) {
                ctx.lose("Wrong path – out of health");
            } else {
                scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "memory-retry");
                    t.setDaemon(true);
                    return t;
                });
                scheduler.schedule(this::nextRound, 1000, TimeUnit.MILLISECONDS);
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

    static final GameDefinition DEFINITION = GameDefinition.builder("memory-path")
            .displayName("Memory Path")
            .description("Watch the path, then step the tiles in the same order.")
            .categories(GameCategory.MEMORY, GameCategory.EDUCATIONAL, GameCategory.PUZZLE)
            .build();

    @Component
    public static class Factory implements GameFactory {
        @Override public String gameId() { return DEFINITION.id(); }
        @Override public GameDefinition definition() { return DEFINITION; }
        @Override public Game<?> create(GameMode mode, int w, int h) { return new MemoryPathGame(mode); }
    }
}
