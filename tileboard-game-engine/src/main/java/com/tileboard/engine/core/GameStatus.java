package com.tileboard.engine.core;

public enum GameStatus {
    /** Registered but not yet started. */
    IDLE,
    /** Running normally. */
    RUNNING,
    /** Temporarily suspended (e.g. level transition). */
    PAUSED,
    /** Finished – result is available. */
    FINISHED,
    /** Aborted due to error or explicit stop. */
    STOPPED
}