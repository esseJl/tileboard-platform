package com.tileboard.gamekit.reaction;

import com.tileboard.serial.board.Position;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The built-in "سرعت واکنش" (reaction speed) capability: a game marks the
 * moment it lit up a target cell ({@link #markPrompt}), and reports every
 * touch it receives to {@link #registerResponse}; the timer figures out
 * whether that touch answered the pending prompt and, if so, how long it
 * took.
 *
 * <p>Thread-safe: prompting typically happens on a clock-tick thread while
 * responses arrive on a touch-input thread. The pending prompt is a single
 * {@link AtomicReference} swapped atomically, so a response can never be
 * matched against a prompt that a concurrent new prompt has already
 * replaced.
 */
public final class ReactionTimer {

    private record Prompt(Position target, Instant shownAt) {
    }

    private final AtomicReference<Prompt> pending = new AtomicReference<>();
    private final List<Duration> history = new CopyOnWriteArrayList<>();

    /** Marks {@code target} as just having been shown/lit, starting the reaction clock for it. Replaces any previous, still-unanswered prompt. */
    public void markPrompt(Position target) {
        pending.set(new Prompt(target, Instant.now()));
    }

    /**
     * Reports a touch at {@code touched}. If it matches the pending prompt's
     * target, the prompt is cleared, the reaction time is recorded, and it
     * is returned; otherwise (wrong cell, or no prompt currently pending)
     * this is a no-op and {@link Optional#empty()} is returned.
     */
    public Optional<Duration> registerResponse(Position touched) {
        Prompt current = pending.get();
        if (current == null || !current.target().equals(touched)) {
            return Optional.empty();
        }
        if (!pending.compareAndSet(current, null)) {
            // A concurrent response or a fresh prompt already won the race for this prompt.
            return Optional.empty();
        }
        Duration reactionTime = Duration.between(current.shownAt(), Instant.now());
        history.add(reactionTime);
        return Optional.of(reactionTime);
    }

    public List<Duration> history() {
        return List.copyOf(history);
    }

    /** Mean reaction time across every recorded response, or {@link Duration#ZERO} if none have been recorded yet. */
    public Duration averageReactionTime() {
        if (history.isEmpty()) {
            return Duration.ZERO;
        }
        long totalMillis = history.stream().mapToLong(Duration::toMillis).sum();
        return Duration.ofMillis(totalMillis / history.size());
    }
}
