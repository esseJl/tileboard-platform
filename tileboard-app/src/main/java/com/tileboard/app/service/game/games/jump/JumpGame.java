package com.tileboard.app.service.game.games.jump;

import com.tileboard.app.service.game.engine.color.TileColor;
import com.tileboard.app.service.game.engine.context.GameContext;
import com.tileboard.app.service.game.engine.core.*;
import com.tileboard.app.service.game.engine.event.GameEventListener;
import com.tileboard.app.service.game.engine.support.NeighborUtils;
import com.tileboard.serial.board.Board;
import com.tileboard.serial.board.Position;
import com.tileboard.serial.board.TileCodec;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Arcade-style moving line / wave that the player must step on.
 * Variants (row / col / diagonal / random) differ only in the movement pattern.
 */
public final class JumpGame implements Game<TileColor> {

    public enum Pattern {
        ROW, COL, DIAGONAL_BR, DIAGONAL_TR, RANDOM
    }

    private final Pattern pattern;
    private final int tickMillis;

    private GameContext<TileColor> ctx;
    private ScheduledExecutorService animator;
    private final AtomicInteger cursor = new AtomicInteger(0);
    private boolean forward = true;
    private Position target;

    public JumpGame(Pattern pattern, GameMode mode) {
        this.pattern = pattern;
        this.tickMillis = switch (mode) {
            case EASY -> 600;
            case NORMAL -> 350;
            case HARD -> 200;
        };
    }

    @Override
    public GameDefinition definition() {
        return switch (pattern) {
            case ROW -> JumpRowFactory.DEFINITION;
            case COL -> JumpColFactory.DEFINITION;
            case DIAGONAL_BR -> JumpDiagBrFactory.DEFINITION;
            case DIAGONAL_TR -> JumpDiagTrFactory.DEFINITION;
            case RANDOM -> RandomJumpFactory.DEFINITION;
        };
    }

    @Override
    public TileCodec<TileColor> tileCodec() {
        return TileColor.CODEC;
    }

    @Override
    public void start(GameContext<TileColor> context) {
        this.ctx = context;
        ctx.fill(TileColor.OFF);
        ctx.score().fullReset();
        ctx.score().setMaxHealth(5);
        ctx.publish();

        animator = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "jump-animator-" + pattern);
            t.setDaemon(true);
            return t;
        });
        animator.scheduleAtFixedRate(this::tick, 0, tickMillis, TimeUnit.MILLISECONDS);

        ctx.timer().startCountdown(Duration.ofMinutes(2), t -> {
            ctx.lose("Time up");
            stop();
        });
    }

    private void tick() {
        if (ctx == null || !ctx.isActive()) {
            return;
        }
        ctx.fill(TileColor.OFF);
        target = nextTarget();
        if (target != null) {
            // light the whole row / col / diagonal segment
            for (Position p : activeSegment(target)) {
                ctx.setTile(p, TileColor.GREEN);
            }
        }
        ctx.publish();
        ctx.timer().tick();
    }

    private Position nextTarget() {
        int w = ctx.width();
        int h = ctx.height();
        return switch (pattern) {
            case ROW -> {
                int r = cursor.get();
                if (forward) {
                    if (r >= h - 1) {
                        forward = false;
                    } else {
                        cursor.incrementAndGet();
                    }
                } else {
                    if (r <= 0) {
                        forward = true;
                    } else {
                        cursor.decrementAndGet();
                    }
                }
                yield new Position(cursor.get(), 0);
            }
            case COL -> {
                int c = cursor.get();
                if (forward) {
                    if (c >= w - 1) {
                        forward = false;
                    } else {
                        cursor.incrementAndGet();
                    }
                } else {
                    if (c <= 0) {
                        forward = true;
                    } else {
                        cursor.decrementAndGet();
                    }
                }
                yield new Position(0, cursor.get());
            }
            case DIAGONAL_BR, DIAGONAL_TR -> {
                int i = cursor.getAndIncrement() % (w + h);
                yield new Position(Math.min(i, h - 1), Math.min(i, w - 1));
            }
            case RANDOM -> ctx.paths().randomPosition();
        };
    }

    private List<Position> activeSegment(Position seed) {
        int w = ctx.width();
        int h = ctx.height();
        return switch (pattern) {
            case ROW -> {
                java.util.ArrayList<Position> list = new java.util.ArrayList<>();
                for (int c = 0; c < w; c++) {
                    list.add(new Position(seed.row(), c));
                }
                yield list;
            }
            case COL -> {
                java.util.ArrayList<Position> list = new java.util.ArrayList<>();
                for (int r = 0; r < h; r++) {
                    list.add(new Position(r, seed.col()));
                }
                yield list;
            }
            case DIAGONAL_BR -> ctx.paths().line(0, 0, NeighborUtils.Direction.DOWN_RIGHT, Math.max(w, h));
            case DIAGONAL_TR -> ctx.paths().line(h - 1, 0, NeighborUtils.Direction.UP_RIGHT, Math.max(w, h));
            case RANDOM -> List.of(seed);
        };
    }

    @Override
    public void onPlayerInput(Board<Boolean> touchedTiles) {
        if (target == null) {
            return;
        }
        List<Position> segment = activeSegment(target);
        boolean hit = false;
        for (Position p : touchedTiles.positionsWhere(Boolean.TRUE::equals)) {
            if (segment.contains(p)) {
                hit = true;
                break;
            }
        }
        if (hit) {
            ctx.score().addScore(1);
            ctx.score().incrementCombo();
            ctx.fire(GameEventListener.GameEvent.combo(ctx.score().combo()));
            // brief flash
            for (Position p : segment) {
                ctx.setTile(p, TileColor.WHITE);
            }
            ctx.publish();
        } else if (touchedTiles.positionsWhere(Boolean.TRUE::equals).size() > 0) {
            ctx.score().resetCombo();
            ctx.score().damage(1);
            if (ctx.score().isGameOver()) {
                ctx.lose("Out of health");
                stop();
            }
        }
    }

    @Override
    public void stop() {
        if (animator != null) {
            animator.shutdownNow();
            animator = null;
        }
        if (ctx != null) {
            ctx.deactivate();
        }
    }

    // --- factories for each variant ---

    @Component
    public static class JumpRowFactory implements GameFactory {
        static final GameDefinition DEFINITION = GameDefinition.builder("jump-row")
                .displayName("Jump Row")
                .description("Step on the moving horizontal line.")
                .categories(GameCategory.ARCADE, GameCategory.EDUCATIONAL_MOTOR)
                .build();

        @Override public String gameId() { return DEFINITION.id(); }
        @Override public GameDefinition definition() { return DEFINITION; }
        @Override public Game<?> create(GameMode mode, int w, int h) { return new JumpGame(Pattern.ROW, mode); }
    }

    @Component
    public static class JumpColFactory implements GameFactory {
        static final GameDefinition DEFINITION = GameDefinition.builder("jump-col")
                .displayName("Jump Column")
                .description("Step on the moving vertical line.")
                .categories(GameCategory.ARCADE, GameCategory.EDUCATIONAL_MOTOR)
                .build();

        @Override public String gameId() { return DEFINITION.id(); }
        @Override public GameDefinition definition() { return DEFINITION; }
        @Override public Game<?> create(GameMode mode, int w, int h) { return new JumpGame(Pattern.COL, mode); }
    }

    @Component
    public static class JumpDiagBrFactory implements GameFactory {
        static final GameDefinition DEFINITION = GameDefinition.builder("jump-diag-br")
                .displayName("Jump Diagonal ↘")
                .description("Step on the moving bottom-right diagonal.")
                .categories(GameCategory.ARCADE, GameCategory.EDUCATIONAL_MOTOR)
                .build();

        @Override public String gameId() { return DEFINITION.id(); }
        @Override public GameDefinition definition() { return DEFINITION; }
        @Override public Game<?> create(GameMode mode, int w, int h) { return new JumpGame(Pattern.DIAGONAL_BR, mode); }
    }

    @Component
    public static class JumpDiagTrFactory implements GameFactory {
        static final GameDefinition DEFINITION = GameDefinition.builder("jump-diag-tr")
                .displayName("Jump Diagonal ↗")
                .description("Step on the moving top-right diagonal.")
                .categories(GameCategory.ARCADE, GameCategory.EDUCATIONAL_MOTOR)
                .build();

        @Override public String gameId() { return DEFINITION.id(); }
        @Override public GameDefinition definition() { return DEFINITION; }
        @Override public Game<?> create(GameMode mode, int w, int h) { return new JumpGame(Pattern.DIAGONAL_TR, mode); }
    }

    @Component
    public static class RandomJumpFactory implements GameFactory {
        static final GameDefinition DEFINITION = GameDefinition.builder("jump-random")
                .displayName("Random Jump")
                .description("Step on randomly appearing target tiles.")
                .categories(GameCategory.ARCADE, GameCategory.EDUCATIONAL_MOTOR)
                .build();

        @Override public String gameId() { return DEFINITION.id(); }
        @Override public GameDefinition definition() { return DEFINITION; }
        @Override public Game<?> create(GameMode mode, int w, int h) { return new JumpGame(Pattern.RANDOM, mode); }
    }
}
