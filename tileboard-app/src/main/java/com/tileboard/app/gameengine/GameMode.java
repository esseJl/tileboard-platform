package com.tileboard.app.gameengine;

/**
 * Generic difficulty/speed knob a {@link Game} may (optionally) react to.
 * Kept here rather than inside any specific game so the concept is
 * available to every future game implementation without redefining it.
 */
public enum GameMode {
    EASY,
    NORMAL,
    HARD
}
