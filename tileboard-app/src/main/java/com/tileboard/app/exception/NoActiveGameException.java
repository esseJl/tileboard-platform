package com.tileboard.app.exception;

import org.springframework.http.HttpStatus;

/** Thrown when a "stop" (or other in-game) operation is requested but no game is running. */
public class NoActiveGameException extends ApiException {

    public NoActiveGameException() {
        super(HttpStatus.CONFLICT, "no_active_game", "No game is currently running.");
    }
}
