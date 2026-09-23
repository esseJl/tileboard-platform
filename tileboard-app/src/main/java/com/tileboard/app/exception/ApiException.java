package com.tileboard.app.exception;

import com.tileboard.serial.support.error.LocalizableException;
import org.springframework.http.HttpStatus;

/**
 * Root of the application's exception hierarchy. Every subclass pins the
 * {@link HttpStatus} it should be translated to, so {@code GlobalExceptionHandler}
 * never has to guess a status code from an exception's identity with an
 * if/else chain - it just asks the exception. Carries an {@code errorCode}
 * and interpolation {@code args} (via {@link LocalizableException}) so the
 * handler can resolve a localized, user-facing message while
 * {@link #getMessage()} keeps the raw, English diagnostic text for logs and
 * the API response's debug field.
 */
public abstract class ApiException extends LocalizableException {

    private final HttpStatus status;

    protected ApiException(HttpStatus status, String errorCode, Object[] args, String rawMessage) {
        super(errorCode, args, rawMessage);
        this.status = status;
    }

    protected ApiException(HttpStatus status, String errorCode, Object[] args, String rawMessage, Throwable cause) {
        super(errorCode, args, rawMessage, cause);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
