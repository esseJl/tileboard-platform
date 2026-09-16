package com.tileboard.app.service.game.engine.core;

/**
 * High-level classification of a game. Used for filtering, UI grouping
 * and selecting default built-in behaviours (e.g. scoring style).
 */
public enum GameCategory {
    /** Classic board / logic puzzles (Dooz, path finding, ...). */
    PUZZLE,
    /** Fast reaction / continuous play (jump lines, waves, ...). */
    ARCADE,
    /** Pure knowledge / memory tasks. */
    EDUCATIONAL,
    /** Educational content combined with motor / spatial skills. */
    EDUCATIONAL_MOTOR,
    /** Explicit two-player competitive modes. */
    COMPETITIVE,
    /** Multi-player / group simultaneous play. */
    GROUP,
    /** Sequence / path memory games. */
    MEMORY,
    /** Music / rhythm synchronisation games. */
    RHYTHM,
    /** Generic / utility (test, echo, diagnostics). */
    UTILITY
}
