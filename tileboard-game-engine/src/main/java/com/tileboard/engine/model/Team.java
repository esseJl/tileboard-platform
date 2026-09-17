package com.tileboard.engine.model;

import java.util.List;
import java.util.Objects;

/**
 * A named group of {@link Player}s for multiplayer/team modes.
 */
public record Team(String name, List<Player> players) {

    public Team {
        Objects.requireNonNull(name,    "name");
        Objects.requireNonNull(players, "players");
        players = List.copyOf(players);
    }

    public int size() { return players.size(); }
}