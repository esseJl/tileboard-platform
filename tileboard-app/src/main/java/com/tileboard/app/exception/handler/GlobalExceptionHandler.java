package com.tileboard.app.exception.handler;

import com.tileboard.app.dto.ApiResponse;
import com.tileboard.app.dto.ApiResponses;
import com.tileboard.app.exception.ApiException;
import com.tileboard.app.i18n.Messages;
import com.tileboard.engine.exception.EngineNotReadyException;
import com.tileboard.engine.exception.GameEngineException;
import com.tileboard.engine.exception.GameNotFoundException;
import com.tileboard.engine.exception.GameSessionException;
import com.tileboard.serial.exception.BoardException;
import com.tileboard.serial.exception.ProtocolException;
import com.tileboard.serial.exception.SerialTransportException;
import com.tileboard.serial.support.error.LocalizableException;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Single place translating exceptions into {@link ApiResponse} bodies.
 * Handlers are ordered from most to least specific; every branch also logs
 * the raw (English) diagnostic so operational issues (e.g. a serial port
 * disappearing) are visible server-side even though the client gets a
 * localized (Persian) {@code message} plus the same raw text in
 * {@code debugMessage}.
 *
 * <p>Message resolution always goes through {@link Messages}, which never
 * throws for a missing catalog key - it falls back to the exception's own
 * {@link Throwable#getMessage()}, so a missing translation degrades to
 * English rather than a 500.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final Messages messages;

    public GlobalExceptionHandler(Messages messages) {
        this.messages = messages;
    }

    /** Resolves the localized message for any {@link LocalizableException} and builds the response body. */
    private ResponseEntity<ApiResponse> respond(HttpStatus status, LocalizableException exception) {
        String localized = messages.resolve(exception.errorCode(), exception.args(), exception.getMessage());
        return ApiResponses.error(localized, exception.getMessage(), status);
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiResponse> handleApiException(ApiException exception) {
        log.warn("{}: {}", exception.errorCode(), exception.getMessage());
        return respond(exception.status(), exception);
    }

    @ExceptionHandler(AsyncRequestTimeoutException.class)
    public void handleAsyncTimeout(AsyncRequestTimeoutException ex) {
        log.debug("Async request timed out: {}", ex.getMessage());
    }

    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleAsyncRequestNotUsable(AsyncRequestNotUsableException ex) {
        log.debug("Client's async connection is no longer usable (client likely disconnected): {}",
                ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse> handleValidationException(MethodArgumentNotValidException exception) {
        // Field-level messages already come out localized: bean-validation annotations use "{key}"
        // placeholders (see the DTOs under com.tileboard.app.dto) resolved against the same message
        // catalog via the fixed "fa" locale configured in application.yml (spring.mvc.locale-resolver).
        String localized = exception.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .map(joined -> messages.get("validation.failed") + " (" + joined + ")")
                .orElse(messages.get("validation.failed"));
        return ApiResponses.badRequest(localized, exception.getMessage());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse> handleTypeMismatch(MethodArgumentTypeMismatchException exception) {
        String expected = exception.getRequiredType() != null ? exception.getRequiredType().getSimpleName() : "the expected type";
        String raw = "'" + exception.getValue() + "' is not a valid value for '" + exception.getName() + "' (expected " + expected + ")";
        String localized = messages.get("validation.type_mismatch", exception.getValue(), exception.getName(), expected);
        return ApiResponses.badRequest(localized, raw);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse> handleUnreadableBody(HttpMessageNotReadableException exception) {
        return ApiResponses.badRequest(messages.get("request.malformed_json"), exception.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse> handleIllegalArgument(IllegalArgumentException exception) {
        return ApiResponses.badRequest(messages.resolve("request.invalid_argument", null, exception.getMessage()),
                exception.getMessage());
    }

    /**
     * The engine module has its own exception hierarchy (it is a
     * framework-free library and cannot depend on {@link ApiException}), so
     * each of its exception types is translated to an {@link ApiResponse}
     * here explicitly, most-specific first, instead of the application
     * defining parallel duplicate exception classes that would need to be
     * kept in sync by hand.
     */
    @ExceptionHandler(EngineNotReadyException.class)
    public ResponseEntity<ApiResponse> handleEngineNotReady(EngineNotReadyException exception) {
        log.warn("{}: {}", exception.errorCode(), exception.getMessage());
        return respond(HttpStatus.CONFLICT, exception);
    }

    @ExceptionHandler(GameNotFoundException.class)
    public ResponseEntity<ApiResponse> handleGameNotFound(GameNotFoundException exception) {
        log.warn("{}: {}", exception.errorCode(), exception.getMessage());
        return respond(HttpStatus.NOT_FOUND, exception);
    }

    @ExceptionHandler(GameSessionException.class)
    public ResponseEntity<ApiResponse> handleGameSessionException(GameSessionException exception) {
        log.warn("{}: {}", exception.errorCode(), exception.getMessage());
        return respond(HttpStatus.CONFLICT, exception);
    }

    /** Catch-all for any other engine failure not covered by a more specific handler above. */
    @ExceptionHandler(GameEngineException.class)
    public ResponseEntity<ApiResponse> handleGameEngineException(GameEngineException exception) {
        log.error("{}", exception.errorCode(), exception);
        return respond(HttpStatus.BAD_GATEWAY, exception);
    }

    /** Anything the tileboard-serial-protocol library raises while talking to real hardware. */
    @ExceptionHandler({SerialTransportException.class, ProtocolException.class, BoardException.class})
    public ResponseEntity<ApiResponse> handleSerialLibraryException(LocalizableException exception) {
        log.error("Serial/protocol failure ({})", exception.errorCode(), exception);
        return respond(HttpStatus.BAD_GATEWAY, exception);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse> handleUnexpectedException(Exception ex, HttpServletResponse response) {
        if (response.isCommitted()) {
            log.warn("Unhandled exception occurred but response is already committed "
                            + "(likely an SSE/streaming response); cannot write error body. Exception: {}",
                    ex.toString());
            return null;
        }
        log.error("Unhandled exception", ex);
        return ResponseEntity.internalServerError()
                .body(ApiResponse.error(messages.get("server.internal_error"), String.valueOf(ex.getMessage())));
    }
}
