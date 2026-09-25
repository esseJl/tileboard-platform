package com.tileboard.engine.feature;

import com.tileboard.engine.feature.shape.ShapeMotionAnimation;
import com.tileboard.engine.feature.shape.ShapeMotionSpec;
import com.tileboard.engine.model.TileColor;
import com.tileboard.serial.board.Board;

import java.util.Map;
import java.util.Random;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public final class AnimationSystem {

    private final int width;
    private final int height;
    private final Consumer<Board<TileColor>> boardPublisher;
    private final ExecutorService executor;
    private final AnimationRegistry registry = new AnimationRegistry();
    private final AnimationContext baseContext;
    private final AtomicLong generation = new AtomicLong(0);
    private final Object runLock = new Object();
    private volatile Future<?> currentTask;
    private volatile CompletableFuture<Void> currentResult;

    public AnimationSystem(int width, int height, Consumer<Board<TileColor>> boardPublisher) {
        this(width, height, boardPublisher, new Random());
    }

    AnimationSystem(int width, int height, Consumer<Board<TileColor>> boardPublisher, Random rng) {
        this.width = width;
        this.height = height;
        this.boardPublisher = boardPublisher;
        this.baseContext = new AnimationContext(width, height, boardPublisher, rng);
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "tileboard-animation");
            t.setDaemon(true);
            return t;
        });
        StandardAnimations.registerAll(registry);
    }

    // ------------------------------------------------------------------ extensibility

    /**
     * Lets game code add/replace named animations without touching this class.
     */
    public void registerAnimation(String key, BoardAnimation animation) {
        registry.register(key, animation);
    }

    public CompletableFuture<Void> play(String key) {
        return play(key, Map.of());
    }

    public CompletableFuture<Void> play(String key, Map<String, Object> params) {
        BoardAnimation animation = registry.require(key);
        AnimationContext ctx = baseContext.withParams(params);
        return run(token -> animation.run(token, ctx));
    }

    /**
     * Runs an ad-hoc {@link BoardAnimation} without registering it (e.g. dynamically built shape motion).
     */
    public CompletableFuture<Void> play(BoardAnimation animation) {
        return run(token -> animation.run(token, baseContext));
    }

    // ------------------------------------------------------------------ existing public API (unchanged)

    public CompletableFuture<Void> playCountdown() {
        return playCountdown(1000);
    }

    public CompletableFuture<Void> playCountdown(long digitDurationMs) {
        String key = (width < 3 || height < 5) ? StandardAnimations.COUNTDOWN_SIMPLE : StandardAnimations.COUNTDOWN_SCALABLE;
        return play(key, Map.of("digitDurationMs", digitDurationMs));
    }

    public CompletableFuture<Void> playWinAnimation() {
        return playWinAnimation(WinAnimationType.RADIAL_BURST);
    }

    public CompletableFuture<Void> playWinAnimation(WinAnimationType type) {
        return play(StandardAnimations.forWin(type));
    }

    public CompletableFuture<Void> playLoseAnimation() {
        return playLoseAnimation(LoseAnimationType.FADE_TO_RED);
    }

    public CompletableFuture<Void> playLoseAnimation(LoseAnimationType type) {
        return play(StandardAnimations.forLose(type));
    }

    public CompletableFuture<Void> playStandbyAnimation() {
        return playStandbyAnimation(StandbyAnimationType.BREATHING);
    }

    public CompletableFuture<Void> playStandbyAnimation(StandbyAnimationType type) {
        return play(StandardAnimations.forStandby(type));
    }

    // ------------------------------------------------------------------ step 2: shape motion convenience

    public CompletableFuture<Void> playShapeMotion(ShapeMotionSpec spec) {
        return play(new ShapeMotionAnimation(spec));
    }

    // ------------------------------------------------------------------ engine plumbing (unchanged)

    private CompletableFuture<Void> run(Consumer<RunToken> body) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        synchronized (runLock) {
            Future<?> previousTask = currentTask;
            CompletableFuture<Void> previousResult = currentResult;

            if (previousTask != null) previousTask.cancel(true);
            if (previousResult != null) previousResult.cancel(false);

            long myGeneration = generation.incrementAndGet();
            RunToken token = new RunToken(myGeneration);
            currentResult = result;
            try {
                Future<?> submitted = executor.submit(() -> {
                    try {
                        body.accept(token);
                        if (token.isCancelled()) result.cancel(false);
                        else result.complete(null);
                    } catch (AnimationCancelledException cancelled) {
                        result.cancel(false);
                    } catch (RuntimeException e) {
                        result.completeExceptionally(e);
                    } finally {
                        Thread.interrupted();
                    }
                });
                currentTask = submitted;
            } catch (RejectedExecutionException e) {
                result.completeExceptionally(e);
            }
        }
        return result;
    }

    public void cancelCurrent() {
        synchronized (runLock) {
            generation.incrementAndGet();
            Future<?> task = currentTask;
            if (task != null) task.cancel(true);
            CompletableFuture<Void> res = currentResult;
            if (res != null) res.cancel(false);
            currentTask = null;
            currentResult = null;
        }
    }

    public void shutdown() {
        cancelCurrent();
        executor.shutdownNow();
        try {
            executor.awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public enum WinAnimationType {RADIAL_BURST, RAINBOW_SWEEP, SPARKLE, FIREWORKS}

    public enum LoseAnimationType {FADE_TO_RED, DESCENDING_CURTAIN, CRUMBLE, PULSE_RED}

    public enum StandbyAnimationType {BREATHING, CORNER_PULSE, WAVE_BORDER, RANDOM_TWINKLE}

    private static final class AnimationCancelledException extends RuntimeException {
        AnimationCancelledException() {
            super(null, null, false, false);
        }
    }

    public final class RunToken {
        private final long myGeneration;

        RunToken(long myGeneration) {
            this.myGeneration = myGeneration;
        }

        public boolean isCancelled() {
            return generation.get() != myGeneration;
        }

        public boolean sleep(long ms) {
            if (isCancelled()) return false;
            if (ms > 0) {
                try {
                    Thread.sleep(ms);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return !isCancelled();
        }

        public void pause(long ms) {
            if (!sleep(ms)) throw new AnimationCancelledException();
        }

        public void show(Board<TileColor> board) {
            if (isCancelled()) throw new AnimationCancelledException();
            boardPublisher.accept(board);
        }

        public void clear() {
            show(new Board<>(width, height, TileColor.OFF));
        }
    }
}