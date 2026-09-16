package com.tileboard.app.common.exception;

import org.springframework.http.HttpStatus;

/** Thrown when a requested game id doesn't match any registered {@code GameFactory}. */
public class GameNotFoundException extends ApiException {

    public GameNotFoundException(String gameId) {
        super(HttpStatus.NOT_FOUND, "game_not_found", "No game registered with id '" + gameId + "'.");
    }
}
