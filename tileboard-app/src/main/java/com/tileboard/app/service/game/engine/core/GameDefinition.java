package com.tileboard.app.service.game.engine.core;

import java.util.Objects;
import java.util.Set;

/**
 * Immutable metadata describing a registered game.
 * Returned by the REST API and used by the session manager for validation.
 */
public record GameDefinition(
        String id,
        String displayName,
        String description,
        Set<GameCategory> categories,
        boolean supportsTwoPlayer,
        boolean supportsGroup
) {
    public GameDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(description, "description");
        categories = categories == null ? Set.of() : Set.copyOf(categories);
    }

    public static Builder builder(String id) {
        return new Builder(id);
    }

    public static final class Builder {
        private final String id;
        private String displayName;
        private String description = "";
        private Set<GameCategory> categories = Set.of();
        private boolean supportsTwoPlayer;
        private boolean supportsGroup;

        private Builder(String id) {
            this.id = id;
            this.displayName = id;
        }

        public Builder displayName(String displayName) {
            this.displayName = displayName;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder categories(GameCategory... categories) {
            this.categories = Set.of(categories);
            return this;
        }

        public Builder supportsTwoPlayer(boolean supportsTwoPlayer) {
            this.supportsTwoPlayer = supportsTwoPlayer;
            return this;
        }

        public Builder supportsGroup(boolean supportsGroup) {
            this.supportsGroup = supportsGroup;
            return this;
        }

        public GameDefinition build() {
            return new GameDefinition(id, displayName, description, categories, supportsTwoPlayer, supportsGroup);
        }
    }
}
