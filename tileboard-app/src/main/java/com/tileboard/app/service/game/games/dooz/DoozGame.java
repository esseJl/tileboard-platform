package com.tileboard.app.service.game.games.dooz;

import com.tileboard.app.service.game.engine.color.TileColor;
import com.tileboard.app.service.game.engine.context.GameContext;
import com.tileboard.app.service.game.engine.core.*;
import com.tileboard.serial.board.Board;
import com.tileboard.serial.board.Position;
import com.tileboard.serial.board.TileCodec;
import org.springframework.stereotype.Component;

/**
 * Two-player Tic-Tac-Toe (Dooz) on a 3×3 (or larger) board.
 * Player 1 = RED, Player 2 = BLUE. Winning line turns WHITE.
 */
public final class DoozGame implements Game<TileColor> {

    private GameContext<TileColor> ctx;
    private TileColor currentPlayer = TileColor.RED;
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
        this.currentPlayer = TileColor.RED;
        ctx.fill(TileColor.OFF);
        ctx.score().fullReset();
        ctx.publish();
    }

    @Override
    public void onPlayerInput(Board<Boolean> touchedTiles) {
        if (finished) {
            return;
        }
        for (Position p : touchedTiles.positionsWhere(Boolean.TRUE::equals)) {
            if (ctx.board().get(p) != TileColor.OFF) {
                continue;
            }
            ctx.setTile(p, currentPlayer);
            ctx.recordTouch(p);
            ctx.publish();

            if (hasWon(currentPlayer)) {
                finished = true;
                highlightWin(currentPlayer);
                ctx.score().addScore(10);
                ctx.win("Player " + (currentPlayer == TileColor.RED ? "1 (RED)" : "2 (BLUE)") + " wins");
                return;
            }
            if (isDraw()) {
                finished = true;
                ctx.fill(TileColor.LIGHT_BLUE);
                ctx.publish();
                ctx.lose("Draw");
                return;
            }
            currentPlayer = currentPlayer == TileColor.RED ? TileColor.BLUE : TileColor.RED;
            break; // one move per input frame
        }
    }

    private boolean hasWon(TileColor player) {
        int n = Math.min(ctx.width(), ctx.height());
        // rows
        for (int r = 0; r < n; r++) {
            boolean ok = true;
            for (int c = 0; c < n; c++) {
                if (ctx.board().get(r, c) != player) {
                    ok = false;
                    break;
                }
            }
            if (ok) return true;
        }
        // cols
        for (int c = 0; c < n; c++) {
            boolean ok = true;
            for (int r = 0; r < n; r++) {
                if (ctx.board().get(r, c) != player) {
                    ok = false;
                    break;
                }
            }
            if (ok) return true;
        }
        // diagonals
        boolean d1 = true, d2 = true;
        for (int i = 0; i < n; i++) {
            if (ctx.board().get(i, i) != player) d1 = false;
            if (ctx.board().get(i, n - 1 - i) != player) d2 = false;
        }
        return d1 || d2;
    }

    private boolean isDraw() {
        int n = Math.min(ctx.width(), ctx.height());
        for (int r = 0; r < n; r++) {
            for (int c = 0; c < n; c++) {
                if (ctx.board().get(r, c) == TileColor.OFF) {
                    return false;
                }
            }
        }
        return true;
    }

    private void highlightWin(TileColor player) {
        // simple: turn all of player's tiles to WHITE
        ctx.board().forEach((r, c, t) -> {
            if (t == player) {
                ctx.setTile(r, c, TileColor.WHITE);
            }
        });
        ctx.publish();
    }

    static final GameDefinition DEFINITION = GameDefinition.builder("dooz")
            .displayName("Dooz (Tic-Tac-Toe)")
            .description("Classic two-player Tic-Tac-Toe. RED vs BLUE.")
            .categories(GameCategory.PUZZLE, GameCategory.COMPETITIVE)
            .supportsTwoPlayer(true)
            .build();

    @Component
    public static class Factory implements GameFactory {
        @Override public String gameId() { return DEFINITION.id(); }
        @Override public GameDefinition definition() { return DEFINITION; }
        @Override public Game<?> create(GameMode mode, int width, int height) { return new DoozGame(); }
    }
}
