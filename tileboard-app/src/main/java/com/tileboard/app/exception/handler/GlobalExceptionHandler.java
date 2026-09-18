package com.tileboard.app.exception.handler;

import com.tileboard.app.exception.ApiException;
import com.tileboard.engine.exception.EngineNotReadyException;
import com.tileboard.engine.exception.GameEngineException;
import com.tileboard.engine.exception.GameNotFoundException;
import com.tileboard.engine.exception.GameSessionException;
import com.tileboard.serial.exception.BoardException;
import com.tileboard.serial.exception.ProtocolException;
import com.tileboard.serial.exception.SerialTransportException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Single place translating exceptions into {@link ProblemDetail} (RFC 7807)
 * responses. Handlers are ordered from most to least specific; every branch
 * also logs so operational issues (e.g. a serial port disappearing) are
 * visible server-side even though the client only gets a clean JSON body.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ProblemDetail handleApiException(ApiException exception) {
        log.warn("{}: {}", exception.errorCode(), exception.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(exception.status(), exception.getMessage());
        problem.setProperty("errorCode", exception.errorCode());
        return problem;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidationException(MethodArgumentNotValidException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("Validation failed");
        problem.setProperty("errors", exception.getBindingResult().getFieldErrors().stream()
                .collect(java.util.stream.Collectors.toMap(
                        org.springframework.validation.FieldError::getField,
                        fieldError -> fieldError.getDefaultMessage() == null ? "invalid" : fieldError.getDefaultMessage(),
                        (a, b) -> a)));
        return problem;
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException exception) {
        String expected = exception.getRequiredType() != null ? exception.getRequiredType().getSimpleName() : "the expected type";
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "'" + exception.getValue() + "' is not a valid value for '" + exception.getName() + "' (expected " + expected + ")");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleUnreadableBody(HttpMessageNotReadableException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request body is missing or malformed JSON.");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    /**
     * The engine module has its own exception hierarchy (it is a
     * framework-free library and cannot depend on {@link ApiException}), so
     * each of its exception types is translated to a {@link ProblemDetail}
     * here explicitly, most-specific first, instead of the application
     * defining parallel duplicate exception classes that would need to be
     * kept in sync by hand.
     */
    @ExceptionHandler(EngineNotReadyException.class)
    public ProblemDetail handleEngineNotReady(EngineNotReadyException exception) {
        log.warn("engine_not_ready: {}", exception.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
        problem.setProperty("errorCode", "engine_not_ready");
        return problem;
    }

    @ExceptionHandler(GameNotFoundException.class)
    public ProblemDetail handleGameNotFound(GameNotFoundException exception) {
        log.warn("game_not_found: {}", exception.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
        problem.setProperty("errorCode", "game_not_found");
        return problem;
    }

    @ExceptionHandler(GameSessionException.class)
    public ProblemDetail handleGameSessionException(GameSessionException exception) {
        log.warn("game_session_error: {}", exception.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
        problem.setProperty("errorCode", "game_session_error");
        return problem;
    }

    /** Catch-all for any other engine failure not covered by a more specific handler above. */
    @ExceptionHandler(GameEngineException.class)
    public ProblemDetail handleGameEngineException(GameEngineException exception) {
        log.error("game_engine_error", exception);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, "Game engine error.");
        problem.setProperty("errorCode", "game_engine_error");
        return problem;
    }

    /** Anything the tileboard-serial-protocol library raises while talking to real hardware. */
    @ExceptionHandler({SerialTransportException.class, ProtocolException.class, BoardException.class})
    public ProblemDetail handleSerialLibraryException(RuntimeException exception) {
        log.error("Serial/protocol failure", exception);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, exception.getMessage());
        problem.setProperty("errorCode", "serial_link_error");
        return problem;
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpectedException(Exception exception) {
        log.error("Unhandled exception", exception);
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected server error.");
    }
}
