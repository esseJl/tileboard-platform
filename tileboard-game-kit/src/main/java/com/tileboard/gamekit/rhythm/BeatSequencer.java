package com.tileboard.gamekit.rhythm;

import com.tileboard.gamekit.time.Cancellable;
import com.tileboard.gamekit.time.GameClock;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The built-in "music &amp; rhythm" capability: fires a callback on every
 * beat of a fixed tempo, built entirely on top of {@link GameClock} (no
 * audio playback of its own - a game supplies its own sound/light cue in
 * the listener). Handles the one piece of bookkeeping every rhythm game
 * needs regardless of its actual gameplay: turning "beats per minute" into
 * a period, and numbering beats so a game can tell "was this touch on
 * beat 7 or beat 8?".
 */
public final class BeatSequencer {

    private final GameClock clock;
    private final Duration beatPeriod;
    private final AtomicLong beatIndex = new AtomicLong();
    private final AtomicReference<Cancellable> running = new AtomicReference<>(Cancellable.noop());

    private BeatSequencer(GameClock clock, Duration beatPeriod) {
        this.clock = clock;
        this.beatPeriod = beatPeriod;
    }

    public static BeatSequencer bpm(GameClock clock, int beatsPerMinute) {
        if (beatsPerMinute <= 0) {
            throw new IllegalArgumentException("beatsPerMinute must be > 0, got " + beatsPerMinute);
        }
        return new BeatSequencer(clock, Duration.ofMillis(60_000L / beatsPerMinute));
    }

    /** The fixed gap between beats, derived from the configured tempo. */
    public Duration beatPeriod() {
        return beatPeriod;
    }

    /** Starts firing {@code listener} every beat, starting one beat from now. Replaces any sequence already running on this instance. */
    public void start(BeatListener listener) {
        Cancellable previous = running.getAndSet(
                clock.scheduleAtFixedRate(beatPeriod, () -> listener.onBeat(beatIndex.incrementAndGet())));
        previous.cancel();
    }

    /** Stops the beat, if running. Safe to call even if never started, or more than once. */
    public void stop() {
        running.getAndSet(Cancellable.noop()).cancel();
    }

    /** The number of beats fired so far (1-based; 0 before the first beat). */
    public long currentBeat() {
        return beatIndex.get();
    }

    /** Invoked once per beat with a 1-based, ever-increasing beat number. */
    @FunctionalInterface
    public interface BeatListener {
        void onBeat(long beatNumber);
    }
}
