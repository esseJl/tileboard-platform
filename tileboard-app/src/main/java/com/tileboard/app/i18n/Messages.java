package com.tileboard.app.i18n;

import java.util.Locale;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

/**
 * Thin wrapper around Spring's {@link MessageSource}, fixed to the
 * application's single supported locale (Persian). Centralizing the locale
 * here means callers (the global exception handler, controllers) never have
 * to think about {@link Locale} at all, and if the app ever needs to support
 * more than one locale, only this class has to change.
 *
 * <p>{@link #resolve} never throws for a missing key: it falls back to
 * {@code fallback} (typically the exception's raw English message), so a
 * missing translation degrades gracefully instead of turning into a 500.
 */
@Component
public class Messages {

    /** Fixed application locale. See {@code spring.mvc.locale} / {@code locale-resolver: fixed} in application.yml. */
    public static final Locale APP_LOCALE = Locale.forLanguageTag("fa");

    private final MessageSource messageSource;

    public Messages(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    /** Resolves {@code code} against the message catalog, or returns {@code fallback} if the key is missing. */
    public String resolve(String code, Object[] args, String fallback) {
        if (code == null) {
            return fallback;
        }
        return messageSource.getMessage(code, args, fallback, APP_LOCALE);
    }

    /** Resolves {@code code} with no fallback text; use only for keys you are certain exist in the catalog. */
    public String get(String code, Object... args) {
        return messageSource.getMessage(code, args, APP_LOCALE);
    }
}
