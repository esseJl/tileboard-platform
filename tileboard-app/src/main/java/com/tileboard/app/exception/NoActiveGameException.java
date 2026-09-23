package com.tileboard.app.exception;

import org.springframework.http.HttpStatus;

/** Thrown when a "stop" (or other in-game) operation is requested but no game is running. */
public class NoActiveGameException extends ApiException {

    public NoActiveGameException() {
        super(HttpStatus.CONFLICT, "game.no_active", null, "No game is currently running.");
    }
}
