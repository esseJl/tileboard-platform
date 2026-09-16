package com.tileboard.gamekit.state;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * The built-in "شمارش امتیاز" (score), "combo" and "level" capability for
 * a single player. Every counter is an independent {@link AtomicInteger},
 * so reads and writes from different threads (a touch-input callback
 * bumping the score, a clock tick reading it to render a HUD) never need
 * external synchronization or see a torn/partial update.
 *
 * <p>For a multiplayer game, one {@code ScoreBoard} per
 * {@link com.tileboard.gamekit.multiplayer.PlayerId} - see
 * {@link com.tileboard.gamekit.multiplayer.PlayerRoster}.
 */
public final class ScoreBoard {

    private final AtomicInteger score = new AtomicInteger();
    private final AtomicInteger combo = new AtomicInteger();
    private final AtomicInteger bestCombo = new AtomicInteger();
    private final AtomicInteger level;

    public ScoreBoard() {
        this(1);
    }

    public ScoreBoard(int startingLevel) {
        this.level = new AtomicInteger(startingLevel);
    }

    public int score() {
        return score.get();
    }

    /** Adds {@code points} to the score (negative to subtract, e.g. a penalty) and returns the new total. */
    public int addScore(int points) {
        return score.addAndGet(points);
    }

    public int combo() {
        return combo.get();
    }

    public int bestCombo() {
        return bestCombo.get();
    }

    /** Extends the current combo by one (e.g. another correct touch in a row) and returns the new combo length. */
    public int incrementCombo() {
        int updated = combo.incrementAndGet();
        bestCombo.updateAndGet(best -> Math.max(best, updated));
        return updated;
    }

    /** Breaks the current combo (e.g. a wrong touch), resetting it to zero. {@link #bestCombo()} is unaffected. */
    public void resetCombo() {
        combo.set(0);
    }

    public int level() {
        return level.get();
    }

    /** Advances to the next level and returns the new level number. */
    public int advanceLevel() {
        return level.incrementAndGet();
    }

    public void setLevel(int newLevel) {
        level.set(newLevel);
    }
}
