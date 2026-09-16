package com.tileboard.app.exception;

import org.springframework.http.HttpStatus;

/**
 * Root of the application's exception hierarchy. Every subclass pins the
 * {@link HttpStatus} it should be translated to, so {@code GlobalExceptionHandler}
 * never has to guess a status code from an exception's identity with an
 * if/else chain - it just asks the exception.
 */
public abstract class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;

    protected ApiException(HttpStatus status, String errorCode, String message) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
    }

    public HttpStatus status() {
        return status;
    }

    /** A short, machine-readable code clients can branch on (e.g. "device_not_configured"). */
    public String errorCode() {
        return errorCode;
    }
}
