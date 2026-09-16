package com.tileboard.app.gameengine;

import com.tileboard.gamekit.time.Cancellable;
import com.tileboard.gamekit.time.GameClock;
import com.tileboard.serial.board.Board;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * The platform surface a {@link Game} is given in order to run: a way to
 * push a frame to the board (and, transitively, to any live SSE dashboard
 * mirroring it - the game itself never knows that happens), a way to ask
 * "how long has this round been running?", and a way to schedule its own
 * periodic animation/timeout ticks. This is the entire surface a game is
 * given, by design - it cannot reach into the serial layer, the registry,
 * or any other game, nor manage its own threads.
 *
 * <p>Reactive games (touch in, board out - see {@code TouchEchoGame}) only
 * ever need {@link #publish}. Games with their own clock - a moving band the
 * player must dodge, a memory-match reveal timer - additionally need
 * {@link #scheduleAtFixedRate} and {@link #elapsed()} so that "keep a
 * background animation/timeout running independently of player input" is a
 * platform capability every such game shares, rather than each one hand-rolling
 * its own {@code ScheduledExecutorService} (and forgetting to shut it down).
 *
 * <p>One {@code GameContext} instance is created per game session and is
 * only valid between {@link Game#start} and {@link Game#stop()};
 * {@code GameSessionManager} owns its lifecycle and calls {@link #close()}
 * right after {@code stop()} to release the scheduler regardless of how
 * many (if any) ticks the game itself scheduled.
 *
 * @param <T> the tile type this game's board is made of (an enum of colors,
 *            a boolean on/off state, ...), matching {@link Game#tileCodec()}
 */
public final class GameContext<T> implements GameClock, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(GameContext.class);
    private static final AtomicInteger CLOCK_THREAD_SEQUENCE = new AtomicInteger();

    private final int width;
    private final int height;
    private final GameMode mode;
    private final Consumer<Board<T>> outputSink;
    private final Instant startedAt = Instant.now();
    /** Lazily created: a reactive game that only ever calls {@link #publish} never needs a clock thread. */
    private volatile ScheduledExecutorService clock;

    public GameContext(int width, int height, GameMode mode, Consumer<Board<T>> outputSink) {
        this.width = width;
        this.height = height;
        this.mode = mode;
        this.outputSink = Objects.requireNonNull(outputSink, "outputSink");
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public GameMode mode() {
        return mode;
    }

    /** How long this game has been running since {@link Game#start} was called. */
    @Override
    public Duration elapsed() {
        return Duration.between(startedAt, Instant.now());
    }

    /** Sends {@code board} to the physical tile board (and any live viewers). */
    public void publish(Board<T> board) {
        outputSink.accept(board);
    }

    /**
     * Runs {@code task} every {@code period} on a dedicated clock thread for
     * this session, starting one {@code period} from now, until either the
     * returned {@link Cancellable} is used or the game is stopped (at which
     * point {@link #close()} tears the clock down regardless). A single
     * game may call this more than once (e.g. one tick for animation, a
     * slower one for a timeout check) - each gets its own independent
     * schedule.
     *
     * <p>An exception escaping {@code task} would otherwise silently kill
     * all future executions of it ({@link ScheduledExecutorService}'s
     * documented behavior); it is logged and swallowed here instead so one
     * bad tick doesn't leave a game frozen with no visible error.
     */
    @Override
    public Cancellable scheduleAtFixedRate(Duration period, Runnable task) {
        Objects.requireNonNull(period, "period");
        Objects.requireNonNull(task, "task");
        long periodMillis = Math.max(1, period.toMillis());
        ScheduledFuture<?> future = clock().scheduleAtFixedRate(
                guarded(task), periodMillis, periodMillis, TimeUnit.MILLISECONDS);
        return () -> future.cancel(false);
    }

    /** Runs {@code task} once, after {@code delay} - e.g. a "reveal, then hide" or "get ready" countdown. Part of the {@link GameClock} contract. */
    @Override
    public Cancellable scheduleOnce(Duration delay, Runnable task) {
        Objects.requireNonNull(delay, "delay");
        Objects.requireNonNull(task, "task");
        long delayMillis = Math.max(0, delay.toMillis());
        ScheduledFuture<?> future = clock().schedule(guarded(task), delayMillis, TimeUnit.MILLISECONDS);
        return () -> future.cancel(false);
    }

    /** Double-checked lazy init: cheap after the first call, and avoids ever synchronizing on the hot {@link #publish} path. */
    private ScheduledExecutorService clock() {
        ScheduledExecutorService instance = clock;
        if (instance == null) {
            synchronized (this) {
                instance = clock;
                if (instance == null) {
                    clock = instance = Executors.newSingleThreadScheduledExecutor(this::newClockThread);
                }
            }
        }
        return instance;
    }

    private Runnable guarded(Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                log.error("Uncaught exception in a scheduled game tick - this tick will not repeat further work", e);
            }
        };
    }

    private Thread newClockThread(Runnable runnable) {
        Thread thread = new Thread(runnable, "game-clock-" + CLOCK_THREAD_SEQUENCE.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    }

    /** Stops the clock thread, if one was ever created. Called by {@code GameSessionManager} once the game has been told to {@link Game#stop()}. */
    @Override
    public void close() {
        ScheduledExecutorService instance = clock;
        if (instance != null) {
            instance.shutdownNow();
        }
    }
}
