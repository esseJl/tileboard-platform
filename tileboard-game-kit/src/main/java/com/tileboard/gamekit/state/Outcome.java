package com.tileboard.gamekit.state;

/** Lifecycle/result of a game or round. */
public enum Outcome {
    IN_PROGRESS,
    WON,
    LOST,
    DRAW,
    CANCELLED;

    public boolean isFinal() {
        return this != IN_PROGRESS;
    }
}
