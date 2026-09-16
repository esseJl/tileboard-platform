package com.tileboard.app.gameengine.games.colormatch;

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

class ColorMatchGameTest {

    private static final GameDefinition DEFINITION = new GameDefinition("color-match", "Color Match", "test");

    private GameContext<TileColor> context;
    private ColorMatchGame game;

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
    void findingTheOnlyPairWinsTheRound() {
        List<Board<TileColor>> published = new CopyOnWriteArrayList<>();
        // A 1x2 board only fits one pair, so both tiles are guaranteed to be that pair's color.
        ColorMatchTuning tuning = new ColorMatchTuning(1, Duration.ofMillis(30), Duration.ofMillis(30));
        game = new ColorMatchGame(DEFINITION, tuning, 2, 1);
        context = new GameContext<>(2, 1, GameMode.NORMAL, published::add);

        game.start(context);
        awaitBoard(published, board -> isFaceDown(board, 0, 0) && isFaceDown(board, 0, 1), Duration.ofSeconds(2));

        game.onPlayerInput(touchAt(0, 0));
        game.onPlayerInput(touchAt(0, 1));

        Board<TileColor> finalBoard = awaitBoard(published,
                board -> board.get(0, 0) != TileColor.OFF && board.get(0, 0) != TileColor.WHITE
                        && board.get(0, 0) == board.get(0, 1),
                Duration.ofSeconds(2));
        assertThat(finalBoard).as("the matching pair should end up revealed and equal").isNotNull();
    }

    private static Board<Boolean> touchAt(int row, int col) {
        Board<Boolean> touched = new Board<>(2, 1, false);
        touched.set(row, col, true);
        return touched;
    }

    private static boolean isFaceDown(Board<TileColor> board, int row, int col) {
        return board.get(row, col) == TileColor.WHITE;
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
