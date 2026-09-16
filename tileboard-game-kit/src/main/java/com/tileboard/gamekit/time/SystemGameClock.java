package com.tileboard.gamekit.time;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Production clock using one daemon scheduler per owner/context. */
public final class SystemGameClock implements GameClock, AutoCloseable {
    private static final AtomicInteger SEQ = new AtomicInteger();
    private final ScheduledExecutorService executor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "tileboard-game-clock-" + SEQ.incrementAndGet());
                t.setDaemon(true);
                return t;
            });

    @Override public Instant now() { return Instant.now(); }

    @Override
    public Cancellable scheduleOnce(Duration delay, Runnable task) {
        require(delay, task);
        ScheduledFuture<?> future = executor.schedule(guard(task), Math.max(0, delay.toMillis()), TimeUnit.MILLISECONDS);
        return () -> future.cancel(false);
    }

    @Override
    public Cancellable scheduleAtFixedRate(Duration period, Runnable task) {
        require(period, task);
        long ms = Math.max(1, period.toMillis());
        ScheduledFuture<?> future = executor.scheduleAtFixedRate(guard(task), ms, ms, TimeUnit.MILLISECONDS);
        return () -> future.cancel(false);
    }

    private static Runnable guard(Runnable task) {
        return () -> {
            try { task.run(); }
            catch (RuntimeException ignored) { /* task isolation; caller should log inside game boundary */ }
        };
    }

    private static void require(Duration d, Runnable task) {
        Objects.requireNonNull(d, "duration");
        Objects.requireNonNull(task, "task");
        if (d.isNegative()) throw new IllegalArgumentException("duration must not be negative");
    }

    @Override public void close() { executor.shutdownNow(); }
}
