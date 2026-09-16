package com.tileboard.gamekit.memory;

import com.tileboard.gamekit.time.RandomSource;
import com.tileboard.serial.board.Position;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The built-in "حافظه" (memory) capability: a fixed assignment of values of
 * type {@code K} to cells, revealed two at a time - a match stays revealed,
 * a mismatch is reported so the caller can flip both back after its own
 * pause. This is exactly the classic memory/concentration ruleset, pulled
 * out of a single hard-coded game (the previous {@code ColorMatchGame}) so
 * any game needing "remember which tile had what" can reuse it with any
 * value type {@code K} (a color, a number, an icon id, ...) instead of
 * re-deriving the pick/evaluate state machine.
 *
 * <p>Deliberately does not own a clock or a "reveal all at the start"
 * timer - those are timing decisions a specific game makes (typically with
 * {@link com.tileboard.gamekit.time.GameClock#countDown}); this class only
 * tracks which two cells are currently picked and which pairs are solved.
 *
 * <p>Thread-safe: every method that reads or mutates picks/solved state is
 * synchronized on an internal lock, matching the pattern the platform's
 * clock-driven games already use for state touched from more than one
 * thread.
 *
 * @param <K> the value type assigned to each cell, e.g. a color enum
 */
public final class RevealChallenge<K> {

    /** The result of {@link #evaluatePendingPair()}. */
    public enum PickResult {MATCHED, MISMATCHED}

    private final Object lock = new Object();
    private final Map<Position, K> assignment;
    private final int totalPairs;
    private final Set<Position> solved = new HashSet<>();
    private Position firstPick;
    private Position secondPick;

    public RevealChallenge(Map<Position, K> assignment) {
        this.assignment = Map.copyOf(assignment);
        if (this.assignment.size() % 2 != 0) {
            throw new IllegalArgumentException("assignment must contain an even number of cells (pairs), got " + assignment.size());
        }
        this.totalPairs = this.assignment.size() / 2;
    }

    /**
     * Builds a random pair assignment: {@code pairCount} values from
     * {@code palette} (its first {@code pairCount} entries), each placed on
     * two randomly chosen, distinct positions out of {@code availablePositions}.
     */
    public static <K> Map<Position, K> randomPairAssignment(List<Position> availablePositions, List<K> palette, int pairCount, RandomSource random) {
        if (pairCount <= 0 || pairCount > palette.size()) {
            throw new IllegalArgumentException("pairCount must be in [1, palette.size()], got " + pairCount);
        }
        if (availablePositions.size() < pairCount * 2) {
            throw new IllegalArgumentException("Not enough positions for " + pairCount + " pairs: need " + (pairCount * 2) + ", have " + availablePositions.size());
        }
        List<Position> shuffled = new ArrayList<>(availablePositions);
        shuffle(shuffled, random);

        Map<Position, K> result = new HashMap<>();
        int next = 0;
        for (int pair = 0; pair < pairCount; pair++) {
            K value = palette.get(pair);
            result.put(shuffled.get(next++), value);
            result.put(shuffled.get(next++), value);
        }
        return result;
    }

    private static void shuffle(List<?> list, RandomSource random) {
        for (int i = list.size() - 1; i > 0; i--) {
            Collections.swap(list, i, random.nextInt(i + 1));
        }
    }

    public K valueAt(Position position) {
        K value = assignment.get(position);
        if (value == null) {
            throw new IllegalArgumentException("Not a challenge cell: " + position);
        }
        return value;
    }

    public Set<Position> cells() {
        return assignment.keySet();
    }

    public int totalPairs() {
        return totalPairs;
    }

    /** Whether {@code position} can currently be picked: it's part of the challenge, not already solved, and not already the pending first pick. */
    public boolean isEligiblePick(Position position) {
        synchronized (lock) {
            return assignment.containsKey(position) && !solved.contains(position) && !position.equals(firstPick);
        }
    }

    /**
     * Registers a pick at {@code position} if there is room for one (fewer
     * than two picks currently pending) and it is {@link #isEligiblePick}.
     * Once this call leaves two picks pending, call
     * {@link #evaluatePendingPair()} (typically after the caller's own
     * "let the player see both" delay) to resolve them.
     *
     * @return true if this call registered the pick, false if it was
     * ineligible or a pair is already pending evaluation
     */
    public boolean registerPick(Position position) {
        synchronized (lock) {
            if (firstPick != null && secondPick != null) {
                return false;
            }
            if (!isEligiblePick(position)) {
                return false;
            }
            if (firstPick == null) {
                firstPick = position;
            } else {
                secondPick = position;
            }
            return true;
        }
    }

    public boolean hasPendingPair() {
        synchronized (lock) {
            return firstPick != null && secondPick != null;
        }
    }

    public Optional<Position> firstPick() {
        synchronized (lock) {
            return Optional.ofNullable(firstPick);
        }
    }

    public Optional<Position> secondPick() {
        synchronized (lock) {
            return Optional.ofNullable(secondPick);
        }
    }

    /**
     * Resolves the two currently pending picks: if their values match, both
     * are added to {@link #solved()} permanently; either way, the pending
     * picks are cleared afterward.
     *
     * @throws IllegalStateException if fewer than two picks are pending
     */
    public PickResult evaluatePendingPair() {
        synchronized (lock) {
            if (firstPick == null || secondPick == null) {
                throw new IllegalStateException("No pending pair to evaluate");
            }
            boolean matched = Objects.equals(assignment.get(firstPick), assignment.get(secondPick));
            if (matched) {
                solved.add(firstPick);
                solved.add(secondPick);
            }
            firstPick = null;
            secondPick = null;
            return matched ? PickResult.MATCHED : PickResult.MISMATCHED;
        }
    }

    /** Whether {@code position} should currently be shown face-up: solved, or one of the (up to two) pending picks. */
    public boolean isFaceUp(Position position) {
        synchronized (lock) {
            return solved.contains(position) || position.equals(firstPick) || position.equals(secondPick);
        }
    }

    public Set<Position> solved() {
        synchronized (lock) {
            return Set.copyOf(solved);
        }
    }

    public int solvedPairs() {
        synchronized (lock) {
            return solved.size() / 2;
        }
    }

    public boolean isComplete() {
        return solvedPairs() >= totalPairs;
    }
}
