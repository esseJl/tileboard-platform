package com.tileboard.serial.support.error;

import java.util.Arrays;

/**
 * Base for runtime exceptions, across every tileboard module, that carry a
 * stable machine-readable {@code errorCode} plus positional {@code args} for
 * message-catalog interpolation - in addition to the plain {@link #getMessage()}
 * raw diagnostic text, which is always in English and is what gets logged
 * server-side and returned to clients as a separate debug field.
 *
 * <p>{@code errorCode} is also the {@code MessageSource} key (see
 * {@code tileboard-app}'s {@code GlobalExceptionHandler} and {@code messages_fa.properties})
 * used to resolve the localized, user-facing message. Modules below
 * {@code tileboard-app} in the dependency graph are intentionally
 * framework-free (no Spring dependency), so the actual resolution against a
 * {@code MessageSource} only happens once, at the HTTP boundary; every
 * exception here just needs to describe itself.
 *
 * <p>This class lives in {@code tileboard-serial-protocol} because it is the
 * most upstream module in the dependency graph
 * (tileboard-app -&gt; tileboard-game-engine -&gt; tileboard-serial-protocol), so
 * every module that needs a localizable exception can depend on it without
 * introducing a new shared module.
 */
public abstract class LocalizableException extends RuntimeException {

    private final String errorCode;
    private final Object[] args;

    protected LocalizableException(String errorCode, Object[] args, String rawMessage) {
        super(rawMessage);
        this.errorCode = errorCode;
        this.args = args == null ? new Object[0] : args.clone();
    }

    protected LocalizableException(String errorCode, Object[] args, String rawMessage, Throwable cause) {
        super(rawMessage, cause);
        this.errorCode = errorCode;
        this.args = args == null ? new Object[0] : args.clone();
    }

    /** Stable, machine-readable code; also the {@code MessageSource} key that resolves the localized message. */
    public final String errorCode() {
        return errorCode;
    }

    /** Positional arguments used to interpolate the localized message template ({0}, {1}, ...). */
    public final Object[] args() {
        return args.clone();
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "[errorCode=" + errorCode + ", args=" + Arrays.toString(args) + "]: " + getMessage();
    }
}
