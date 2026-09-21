package com.tileboard.engine.core;

import java.util.concurrent.atomic.AtomicReference;

final class SessionLifecycle {
    private final AtomicReference<GameStatus> status = new AtomicReference<>(GameStatus.IDLE);

    boolean start() {
        return status.compareAndSet(GameStatus.IDLE, GameStatus.RUNNING);
    }

    boolean finish(GameStatus target) {
        GameStatus prev;
        do {
            prev = status.get();
            if (prev != GameStatus.RUNNING && prev != GameStatus.PAUSED) return false;
        } while (!status.compareAndSet(prev, target));
        return true;
    }

    GameStatus current() {
        return status.get();
    }
}
