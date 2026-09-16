package com.tileboard.app.gameengine.games.jump;

import com.tileboard.app.gameengine.GameContext;
import com.tileboard.app.gameengine.GameDefinition;
import com.tileboard.app.gameengine.GameMode;
import com.tileboard.app.gameengine.TileColor;
import com.tileboard.serial.board.Board;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

class JumpGameTest {

    private static final GameDefinition DEFINITION = new GameDefinition("jump", "Jump", "test");

    private GameContext<TileColor> context;
    private JumpGame game;

    @AfterEach
    void tearDown() {
        if (game != null) {
            game.stop();
        }
        if (context != null) {
            context.close();
        }
    }

    @Test
    void survivingTheRoundDurationIsAWin() {
        List<Board<TileColor>> published = new CopyOnWriteArrayList<>();
        game = new JumpGame(DEFINITION, JumpPatterns.row(),
                new JumpTuning(Duration.ofMillis(20), 5, Duration.ofMillis(150)), 2, 2);
        context = new GameContext<>(2, 2, GameMode.NORMAL, published::add);

        game.start(context);

        Board<TileColor> finalBoard = awaitBoard(published, board -> isSolidColor(board, TileColor.WHITE), Duration.ofSeconds(2));
        assertThat(finalBoard).as("round should end with an all-white 'win' board").isNotNull();
    }

    @Test
    void touchingTheBandUntilOutOfLivesIsALoss() {
        List<Board<TileColor>> published = new CopyOnWriteArrayList<>();
        // 1x1 board + row() pattern: the single tile is always part of the band, so every touch is a hit.
        game = new JumpGame(DEFINITION, JumpPatterns.row(),
                new JumpTuning(Duration.ofSeconds(30), 2, Duration.ofSeconds(30)), 1, 1);
        context = new GameContext<>(1, 1, GameMode.NORMAL, published::add);

        game.start(context);
        game.onPlayerInput(touchAt(0, 0));
        assertThat(published).noneMatch(board -> isSolidColor(board, TileColor.RED));

        game.onPlayerInput(touchAt(0, 0));

        Board<TileColor> finalBoard = awaitBoard(published, board -> isSolidColor(board, TileColor.RED), Duration.ofSeconds(1));
        assertThat(finalBoard).as("running out of lives should end the round with an all-red 'loss' board").isNotNull();
    }

    private static Board<Boolean> touchAt(int row, int col) {
        Board<Boolean> touched = new Board<>(1, 1, false);
        touched.set(row, col, true);
        return touched;
    }

    private static boolean isSolidColor(Board<TileColor> board, TileColor color) {
        return board.positionsWhere(color::equals).size() == board.area();
    }

    private static Board<TileColor> awaitBoard(List<Board<TileColor>> published, Predicate<Board<TileColor>> predicate, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            for (Board<TileColor> board : published) {
                if (predicate.test(board)) {
                    return board;
                }
            }
            sleep(Duration.ofMillis(20));
        }
        return null;
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
