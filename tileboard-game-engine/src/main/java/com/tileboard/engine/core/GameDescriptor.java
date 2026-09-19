package com.tileboard.engine.core;

import java.util.Objects;

/**
 * Static metadata about a game type – its id, human-readable name, category
 * and board requirements. Supplied by each {@link Game} implementation so
 * the engine can list and auto-register games without instantiating them.
 */
public record GameDescriptor(String gameId, String displayName,
                             String category, String description,
                             int requiredWidth, int requiredHeight,
                             int minPlayers, int maxPlayers) {
    public GameDescriptor {
        Objects.requireNonNull(gameId, "gameId");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(description, "description");
        if (requiredWidth <= 0) throw new IllegalArgumentException("requiredWidth must be > 0");
        if (requiredHeight <= 0) throw new IllegalArgumentException("requiredHeight must be > 0");
        if (minPlayers < 1) throw new IllegalArgumentException("minPlayers must be >= 1");
        if (maxPlayers < minPlayers) throw new IllegalArgumentException("maxPlayers must be >= minPlayers");
    }

    /**
     * Convenience builder for single-player, any-size games.
     */
    public static Builder builder(String gameId, String displayName) {
        return new Builder(gameId, displayName);
    }

    public static final class Builder {
        private final String gameId;
        private final String displayName;
        private String category = "GENERAL";
        private String description = "";
        private int requiredWidth = 8;
        private int requiredHeight = 8;
        private int minPlayers = 1;
        private int maxPlayers = 1;

        private Builder(String gameId, String displayName) {
            this.gameId = Objects.requireNonNull(gameId);
            this.displayName = Objects.requireNonNull(displayName);
        }

        public Builder category(String category) {
            this.category = category;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder boardSize(int w, int h) {
            this.requiredWidth = w;
            this.requiredHeight = h;
            return this;
        }

        public Builder players(int min, int max) {
            if (min < 1) throw new IllegalArgumentException("minPlayers must be >= 1");
            if (max < min) throw new IllegalArgumentException("maxPlayers must be >= minPlayers");
            this.minPlayers = min;
            this.maxPlayers = max;
            return this;
        }

        public GameDescriptor build() {
            return new GameDescriptor(gameId, displayName, category, description,
                    requiredWidth, requiredHeight, minPlayers, maxPlayers);
        }
    }
}