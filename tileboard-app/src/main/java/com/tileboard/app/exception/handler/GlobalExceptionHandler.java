package com.tileboard.app.exception.handler;

import com.tileboard.app.dto.ApiResponse;
import com.tileboard.app.dto.ApiResponses;
import com.tileboard.app.exception.ApiException;
import com.tileboard.engine.exception.EngineNotReadyException;
import com.tileboard.engine.exception.GameEngineException;
import com.tileboard.engine.exception.GameNotFoundException;
import com.tileboard.engine.exception.GameSessionException;
import com.tileboard.serial.exception.BoardException;
import com.tileboard.serial.exception.ProtocolException;
import com.tileboard.serial.exception.SerialTransportException;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Single place translating exceptions into {@link ProblemDetail} (RFC 7807)
 * responses. Handlers are ordered from most to least specific; every branch
 * also logs so operational issues (e.g. a serial port disappearing) are
 * visible server-side even though the client only gets a clean JSON body.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiResponse> handleApiException(ApiException exception) {
        log.warn("{}: {}", exception.errorCode(), exception.getMessage());
        return ApiResponses.error(exception.getMessage(),exception.status());
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
        return ApiResponses.badRequest(exception.getMessage());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse> handleTypeMismatch(MethodArgumentTypeMismatchException exception) {
        String expected = exception.getRequiredType() != null ? exception.getRequiredType().getSimpleName() : "the expected type";
        return ApiResponses.badRequest("'" + exception.getValue() + "' is not a valid value for '" + exception.getName() + "' (expected " + expected + ")");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse> handleUnreadableBody(HttpMessageNotReadableException exception) {
        return ApiResponses.badRequest("Request body is missing or malformed JSON.");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse> handleIllegalArgument(IllegalArgumentException exception) {
        return ApiResponses.badRequest( exception.getMessage());
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
    public ResponseEntity<ApiResponse> handleEngineNotReady(EngineNotReadyException exception) {
        log.warn("engine_not_ready: {}", exception.getMessage());
        return ApiResponses.conflict(exception.getMessage());
    }

    @ExceptionHandler(GameNotFoundException.class)
    public ResponseEntity<ApiResponse> handleGameNotFound(GameNotFoundException exception) {
        log.warn("game_not_found: {}", exception.getMessage());
    return  ApiResponses.notFound(exception.getMessage());
    }

    @ExceptionHandler(GameSessionException.class)
    public ResponseEntity<ApiResponse> handleGameSessionException(GameSessionException exception) {
        log.warn("game_session_error: {}", exception.getMessage());
        return ApiResponses.conflict(exception.getMessage());
    }

    /** Catch-all for any other engine failure not covered by a more specific handler above. */
    @ExceptionHandler(GameEngineException.class)
    public ResponseEntity<ApiResponse> handleGameEngineException(GameEngineException exception) {
        log.error("game_engine_error", exception);
        return ApiResponses.badGateway(exception.getMessage());
    }

    /** Anything the tileboard-serial-protocol library raises while talking to real hardware. */
    @ExceptionHandler({SerialTransportException.class, ProtocolException.class, BoardException.class})
    public ResponseEntity<ApiResponse> handleSerialLibraryException(RuntimeException exception) {
        log.error("Serial/protocol failure", exception);
    return ApiResponses.badGateway(exception.getMessage());
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
        return ResponseEntity.internalServerError().body(ApiResponse.error(ex.getMessage()));
    }
}
