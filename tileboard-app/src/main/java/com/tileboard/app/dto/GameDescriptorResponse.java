package com.tileboard.app.dto;

import com.tileboard.engine.core.GameDescriptor;

public record GameDescriptorResponse(String gameId, String displayName, String category, String description,
                                        int requiredWidth, int requiredHeight,
                                        int minPlayers, int maxPlayers) {
    public static GameDescriptorResponse from(GameDescriptor descriptor) {
        return new GameDescriptorResponse(
                descriptor.gameId(),
                descriptor.displayName(),
                descriptor.category(),
                descriptor.description(),
                descriptor.requiredWidth(),
                descriptor.requiredHeight(),
                descriptor.minPlayers(),
                descriptor.maxPlayers());
    }
}
