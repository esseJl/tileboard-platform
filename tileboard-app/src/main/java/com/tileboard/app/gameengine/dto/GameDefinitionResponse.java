package com.tileboard.app.gameengine.dto;

import com.tileboard.app.gameengine.GameDefinition;
import com.tileboard.gamekit.catalog.GameGenre;

import java.util.Set;

public record GameDefinitionResponse(String id, String displayName, String description, Set<GameGenre> genres) {

    public static GameDefinitionResponse from(GameDefinition definition) {
        return new GameDefinitionResponse(definition.id(), definition.displayName(), definition.description(), definition.genres());
    }
}
