package com.tileboard.app.service.game.engine.score;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Mutable, thread-safe score / health / combo / level container.
 * Built-in for every game session.
 */
public final class ScoreState {

    private final AtomicInteger score = new AtomicInteger(0);
    private final AtomicInteger health = new AtomicInteger(3);
    private final AtomicInteger combo = new AtomicInteger(0);
    private final AtomicInteger maxCombo = new AtomicInteger(0);
    private final AtomicInteger level = new AtomicInteger(1);
    private final AtomicInteger wins = new AtomicInteger(0);
    private final AtomicInteger losses = new AtomicInteger(0);

    private volatile int maxHealth = 3;
    private volatile boolean gameOver;

    public int score() {
        return score.get();
    }

    public int health() {
        return health.get();
    }

    public int combo() {
        return combo.get();
    }

    public int maxCombo() {
        return maxCombo.get();
    }

    public int level() {
        return level.get();
    }

    public int wins() {
        return wins.get();
    }

    public int losses() {
        return losses.get();
    }

    public boolean isGameOver() {
        return gameOver;
    }

    public void addScore(int delta) {
        score.addAndGet(delta);
    }

    public void setScore(int value) {
        score.set(value);
    }

    public void resetCombo() {
        combo.set(0);
    }

    public void incrementCombo() {
        int c = combo.incrementAndGet();
        maxCombo.updateAndGet(m -> Math.max(m, c));
    }

    public void damage(int amount) {
        int h = health.addAndGet(-amount);
        if (h <= 0) {
            health.set(0);
            gameOver = true;
        }
    }

    public void heal(int amount) {
        health.updateAndGet(h -> Math.min(maxHealth, h + amount));
    }

    public void setMaxHealth(int maxHealth) {
        this.maxHealth = Math.max(1, maxHealth);
        health.set(this.maxHealth);
    }

    public void levelUp() {
        level.incrementAndGet();
    }

    public void setLevel(int level) {
        this.level.set(Math.max(1, level));
    }

    public void recordWin() {
        wins.incrementAndGet();
    }

    public void recordLoss() {
        losses.incrementAndGet();
        gameOver = true;
    }

    public void resetForNewRound() {
        combo.set(0);
        gameOver = false;
    }

    public void fullReset() {
        score.set(0);
        health.set(maxHealth);
        combo.set(0);
        maxCombo.set(0);
        level.set(1);
        wins.set(0);
        losses.set(0);
        gameOver = false;
    }
}
