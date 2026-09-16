package com.tileboard.gamekit.catalog;

/**
 * Catalog tags a {@code GameFactory} can attach to its
 * {@code GameDefinition} so a client can filter/group the game picker by
 * kind of play, without the engine itself ever branching on genre - this
 * is metadata only, purely additive, and a game may carry more than one
 * tag (e.g. an educational reflex game is both {@link #EDUCATIONAL_MOTOR}
 * and {@link #ARCADE}).
 */
public enum GameGenre {
    /** Logic/problem-solving, typically with a single correct solution (e.g. memory/concentration). */
    PUZZLE,
    /** Fast-paced, reflex/score-driven play. */
    ARCADE,
    /** Primarily teaches a concept (colors, counting, letters, ...). */
    EDUCATIONAL,
    /** Educational and gross-motor/movement-driven at once (e.g. touch the correct color as it appears). */
    EDUCATIONAL_MOTOR,
    /** Head-to-head, two-player competitive play. */
    COMPETITIVE,
    /** Team/group play, typically more than two participants. */
    GROUP
}
