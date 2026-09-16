package com.tileboard.app.gameengine;

import com.tileboard.gamekit.catalog.GameGenre;

import java.util.Set;

/**
 * Static metadata describing a game that can be selected and played -
 * everything a client needs to render a game picker (including, via
 * {@link #genres}, filtering/grouping it by kind of play), without
 * knowing anything about the game's internal rules.
 *
 * <p>{@link #genres} is the game-kit's built-in puzzle/arcade/educational
 * catalog capability ({@link GameGenre}); it is pure metadata; the engine
 * itself never branches on it.
 */
public record GameDefinition(String id, String displayName, String description, Set<GameGenre> genres) {

    public GameDefinition {
        genres = Set.copyOf(genres);
    }

    /** {@link #GameDefinition(String, String, String, Set)} with no genre tags. */
    public GameDefinition(String id, String displayName, String description) {
        this(id, displayName, description, Set.of());
    }
}
