package com.tileboard.app.gameengine;

/**
 * Static metadata describing a game that can be selected and played -
 * everything a client needs to render a game picker, without knowing
 * anything about the game's internal rules.
 */
public record GameDefinition(String id, String displayName, String description) {
}
