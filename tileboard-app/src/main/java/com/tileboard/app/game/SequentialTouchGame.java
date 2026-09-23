package com.tileboard.app.game;

import com.tileboard.engine.core.*;
import com.tileboard.engine.feature.AnimationSystem;
import com.tileboard.engine.model.TileColor;
import com.tileboard.engine.model.TileEvent;
import com.tileboard.engine.model.TileEventType;
import com.tileboard.serial.board.Position;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Sample tutorial game: Sequential Tile Touch
 *
 * <h2>Game Scenario</h2>
 * <ol>
 *   <li>Board turns off, standby animation (BREATHING) shows for 2 seconds</li>
 *   <li>Countdown animation (3 -> 2 -> 1) plays</li>
 *   <li>All board tiles are stored in a row-major list (0,0) -> (0,1) ...</li>
 *   <li>Current tile lights up with a color from palette</li>
 *   <li>Player must touch exactly that tile:
 *       <ul>
 *         <li>Correct touch -> +10 points, tile off, next tile's turn</li>
 *         <li>Wrong touch -> short lose animation (FADE_TO_RED), no penalty but current tile re-lights</li>
 *       </ul>
 *   </li>
 *   <li>When all tiles touched, win animation (RADIAL_BURST) then game ends with victory</li>
 *   <li>If player does not finish in 90 seconds, lose animation (DESCENDING_CURTAIN) and defeat</li>
 * </ol>
 *
 * <h2>Thread-Safety and Concurrency Notes</h2>
 * <p>
 * This game follows the stateless {@link Game} contract:
 * It keeps no mutable fields in the class itself. All session state (current index, positions list)
 * is stored inside {@link GameContext#state()} which is itself synchronized (HashMap with synchronized methods).
 * </p>
 * <p>
 * Board access goes through {@link BoardChannel} which:
 * <ul>
 *   <li>Uses {@link ReentrantLock} to protect internal buffer</li>
 *   <li>Uses a separate Object called gatewayWriteLock to serialize writes on the wire</li>
 *   <li>Even if multiple threads call setTile concurrently, the latest consistent snapshot is sent (coalescing semantics)</li>
 * </ul>
 * </p>
 * <p>
 * Animations in {@link AnimationSystem} run on a SingleThreadExecutor.
 * Each new animation cancels previous one by incrementing AtomicLong generation (cooperative cancellation).
 * RunToken.isCancelled() is checked before every sleep/show and throws AnimationCancelledException if cancelled.
 * </p>
 */
public class SequentialTouchGame implements Game {

    private static final Logger log = LoggerFactory.getLogger(SequentialTouchGame.class);

    // Keys stored in GameState (thread-safe bag)
    private static final String KEY_POSITIONS = "sequential.positions";
    private static final String KEY_INDEX = "sequential.index";
    private static final String KEY_TOTAL = "sequential.total";

    // Color palette for lighting tiles sequentially
    private static final TileColor[] PALETTE = {
            TileColor.RED, TileColor.GREEN, TileColor.BLUE,
            TileColor.YELLOW, TileColor.PINK, TileColor.LIGHT_BLUE, TileColor.WHITE
    };

    private final GameDescriptor descriptor;

    public SequentialTouchGame() {
        // This game works on any board size but 4x4 or 8x8 is recommended for simplicity
        // requiredWidth/Height must match DeviceConfiguration
        // Otherwise GameEngineImpl.validateBoardSize will throw
        this.descriptor = GameDescriptor.builder("sequential-touch", "Sequential Touch Challenge")
                .category("TUTORIAL")
                .description("Tiles light up sequentially; touch it to score and advance. Includes countdown, standby, win and lose animations.")
                .boardSize(3, 3) // default 3x3, changeable as needed
                .players(1, 1)
                .build();
    }

    public SequentialTouchGame(int width, int height) {
        this.descriptor = GameDescriptor.builder("sequential-touch", "Sequential Touch Challenge")
                .category("TUTORIAL")
                .description("Tiles light up sequentially; touch it to score and advance to next tile.")
                .boardSize(width, height)
                .players(1, 1)
                .build();
    }

    @Override
    public GameDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public void onStart(GameContext ctx) {
        log.info("[{}] SequentialTouchGame onStart - board {}x{}", ctx.sessionId(), ctx.boardWidth(), ctx.boardHeight());

        // 1. Turn board off and reset scores
        ctx.fillBoard(TileColor.OFF);
        ctx.scores().resetAll();
        ctx.state().clear();

        // 2. Standby animation: idle state before start
        // This animation runs infinitely until cancelled, so we run it for 2 seconds then cancel
        // AnimationSystem implementation: cancelCurrent() increments generation and cancels previous Future
        try {
            log.info("[{}] Playing STANDBY (BREATHING) for 2 seconds...", ctx.sessionId());
            ctx.animations().playStandbyAnimation(AnimationSystem.StandbyAnimationType.BREATHING)
                    .get(2, TimeUnit.SECONDS);
        } catch (Exception e) {
            // If timeout, animation still running (because infinite) - cancel it
            ctx.animations().cancelCurrent();
            log.info("[{}] Standby cancelled, moving to countdown", ctx.sessionId());
        }

        // 3. Countdown animation: 3 -> 2 -> 1 -> green blink
        // playCountdown runs inside a SingleThreadExecutor and does not block until we join
        // We join so player gets ready
        try {
            ctx.animations().playCountdown(1000).join();
            log.info("[{}] Playing COUNTDOWN...", ctx.sessionId());
        } catch (Exception e) {
            log.warn("[{}] Countdown interrupted", ctx.sessionId(), e);
        }

        // 4. List all positions in row-major order
        List<Position> allPositions = new ArrayList<>();
        for (int r = 0; r < ctx.boardHeight(); r++) {
            for (int c = 0; c < ctx.boardWidth(); c++) {
                allPositions.add(new Position(r, c));
            }
        }

        ctx.state().put(KEY_POSITIONS, allPositions);
        ctx.state().put(KEY_INDEX, 0);
        ctx.state().put(KEY_TOTAL, allPositions.size());

        // 5. Global game timer: if not finished in 90 seconds, lose
        // GameTimer uses AtomicReference<Runnable> for onExpire and volatile Instant for times
        // checkExpiry() is called every tick (100ms) by GameSessionImpl.runTick()
        ctx.timer().startCountdown(Duration.ofSeconds(90), () -> {
            log.info("[{}] Timer expired - player LOST", ctx.sessionId());
            // Lose animation then defeat
            // This callback runs on tick thread, so we should not block long
            // Start animation async then loseSession
            ctx.animations().playLoseAnimation(AnimationSystem.LoseAnimationType.DESCENDING_CURTAIN)
                    .thenRun(() -> ctx.loseSession());
        });

        // 6. Light up first tile
        lightCurrentTile(ctx);

        log.info("[{}] Game started with {} tiles", ctx.sessionId(), allPositions.size());
    }

    @Override
    public void onTileEvent(GameContext ctx, TileEvent event) {
        // This method is only called when GameStatus=RUNNING by GameSessionImpl.handleTileEvent
        // handleTileEvent itself records touchHistory and reactionSpeed before calling us

        // Read state from GameState (thread-safe: synchronized methods)
        @SuppressWarnings("unchecked")
        List<Position> positions = ctx.state().get(KEY_POSITIONS, List.class).orElse(List.of());
        int currentIndex = ctx.state().getOrDefault(KEY_INDEX, Integer.class, 0);

        if (positions.isEmpty() || currentIndex >= positions.size()) {
            // Game already finished
            return;
        }

        Position expected = positions.get(currentIndex);
        Position touched = null;
        if (event.type()== TileEventType.TOUCH){
            touched = event.position();
        }


        log.debug("[{}] Touch at {} - expected {}", ctx.sessionId(), touched, expected);

        if (Objects.nonNull(touched) &&touched.equals(expected)) {
            // Correct touch
            handleCorrectTouch(ctx, currentIndex, positions);
        } else {
            // Wrong touch: short lose animation then back
            handleWrongTouch(ctx);
        }
    }

    private void handleCorrectTouch(GameContext ctx, int currentIndex, List<Position> positions) {
        String playerId = ctx.players().get(0).id();

        // Add score - ScoreSystem uses ConcurrentHashMap<String, AtomicInteger>
        // add() with AtomicInteger.addAndGet is thread-safe
        int newScore = ctx.scores().add(playerId, 10);
        log.info("[{}] Correct! Tile {}/{} touched, score={}", ctx.sessionId(), currentIndex + 1, positions.size(), newScore);

        // Turn off current tile
        Position justTouched = positions.get(currentIndex);
        ctx.setTile(justTouched.row(), justTouched.col(), TileColor.OFF);

        // Move to next tile
        int nextIndex = currentIndex + 1;
        ctx.state().put(KEY_INDEX, nextIndex);

        if (nextIndex >= positions.size()) {
            // All tiles done -> win
            handleWin(ctx);
        } else {
            // Light next tile
            lightCurrentTile(ctx);
        }
    }

    private void handleWrongTouch(GameContext ctx) {
        log.info("[{}] Wrong tile touched!", ctx.sessionId());

        // Short lose animation: FADE_TO_RED (about 1.5 sec)
        // Since AnimationSystem has only one animation at a time, this temporarily overrides current tile
        // After finish, re-light current tile
        ctx.animations().playLoseAnimation(AnimationSystem.LoseAnimationType.FADE_TO_RED)
                .thenRun(() -> {
                    // This thenRun runs on animation thread, but setTile is thread-safe (BoardChannel)
                    lightCurrentTile(ctx);
                });
    }

    private void handleWin(GameContext ctx) {
        log.info("[{}] All tiles touched! Player WINS", ctx.sessionId());

        // Stop timer
        ctx.timer().stop();

        // Win animation: RADIAL_BURST
        // Then winSession which triggers finishSession in GameSessionImpl
        // finishSession with CAS (compareAndSet) guarantees it runs only once (SessionLifecycle)
        ctx.animations().playWinAnimation(AnimationSystem.WinAnimationType.RADIAL_BURST)
                .thenRun(() -> {
                    // winSession takes winners list
                    ctx.winSession(ctx.players());
                });
    }

    private void lightCurrentTile(GameContext ctx) {
        @SuppressWarnings("unchecked")
        List<Position> positions = ctx.state().get(KEY_POSITIONS, List.class).orElse(List.of());
        int index = ctx.state().getOrDefault(KEY_INDEX, Integer.class, 0);

        if (index < 0 || index >= positions.size()) return;

        Position pos = positions.get(index);
        // Color selected from palette based on index for variety
        TileColor color = PALETTE[index % PALETTE.length];

        // BoardChannel.setTile -> stateLock (ReentrantLock) for buffer copy + gatewayWriteLock (synchronized) for sending
        // This guarantees even if onTileEvent and onTick call setTile concurrently, frames don't interleave on wire
        ctx.setTile(pos.row(), pos.col(), color);

        log.debug("[{}] Lit tile {} at {} with {}", ctx.sessionId(), index, pos, color);
    }

    @Override
    public void onStop(GameContext ctx, GameResult result) {
        log.info("[{}] SequentialTouchGame onStop - status={}, scores={}", ctx.sessionId(), result.finalStatus(), result.scoreByPlayerId().get(0));

        // Turn board off (best-effort)
        try {
            ctx.fillBoard(TileColor.OFF);
        } catch (Exception e) {
            log.warn("[{}] Could not clear board on stop (gateway may be disconnected)", ctx.sessionId());
        }

        // Cancel final animations and release resources
        ctx.animations().cancelCurrent();
    }

    @Override
    public void onError(GameContext ctx, Throwable error) {
        log.error("[{}] Game error", ctx.sessionId(), error);
        // On error, lose animation then stop session
        try {
            ctx.animations().playLoseAnimation(AnimationSystem.LoseAnimationType.PULSE_RED)
                    .get(2, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("[{}] Lose animation interrupted on error", ctx.sessionId());
        } finally {
            ctx.stopSession();
        }
    }
}
