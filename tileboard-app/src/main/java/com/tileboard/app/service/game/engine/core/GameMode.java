package com.tileboard.app.service.game.engine.core;

/**
 * Difficulty / intensity of a game session.
 * Maps to concrete numeric parameters (speed, path length, reaction window, ...)
 * inside each {@link GameFactory}.
 */
public enum GameMode {
    EASY,
    NORMAL,
    HARD
}
