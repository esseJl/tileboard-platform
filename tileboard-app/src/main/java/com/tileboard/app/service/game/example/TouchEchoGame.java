package com.tileboard.app.service.game.example;

import com.tileboard.app.service.game.engine.color.TileColor;
import com.tileboard.app.service.game.engine.context.GameContext;
import com.tileboard.app.service.game.engine.core.*;
import com.tileboard.serial.board.Board;
import com.tileboard.serial.board.Position;
import com.tileboard.serial.board.TileCodec;
import org.springframework.stereotype.Component;

/**
 * Minimal reference game that lights every currently pressed tile in green.
 * Proves the whole pipeline (factory → registry → session → publish) works.
 */
public final class TouchEchoGame implements Game<TileColor> {

    private GameContext<TileColor> ctx;

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
        ctx.fill(TileColor.OFF);
        ctx.publish();
    }

    @Override
    public void onPlayerInput(Board<Boolean> touchedTiles) {
        ctx.fill(TileColor.OFF);
        for (Position p : touchedTiles.positionsWhere(Boolean.TRUE::equals)) {
            ctx.setTile(p, TileColor.GREEN);
        }
        ctx.publish();
    }

    static final GameDefinition DEFINITION = GameDefinition.builder("touch-echo")
            .displayName("Touch Echo")
            .description("Lights every currently pressed tile. Diagnostic / reference game.")
            .categories(GameCategory.UTILITY)
            .build();

    @Component
    public static class Factory implements GameFactory {
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
            return new TouchEchoGame();
        }
    }
}
