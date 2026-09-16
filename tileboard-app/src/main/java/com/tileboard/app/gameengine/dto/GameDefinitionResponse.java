package com.tileboard.app.gameengine.dto;

import com.tileboard.app.gameengine.GameDefinition;

public record GameDefinitionResponse(String id, String displayName, String description) {

    public static GameDefinitionResponse from(GameDefinition definition) {
        return new GameDefinitionResponse(definition.id(), definition.displayName(), definition.description());
    }
}
