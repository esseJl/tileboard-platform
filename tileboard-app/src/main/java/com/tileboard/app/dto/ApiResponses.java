package com.tileboard.app.dto;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

public final class ApiResponses {

    private ApiResponses() {
    }

    public static ResponseEntity<ApiResponse> ok() {
        return ResponseEntity.ok(ApiResponse.success());
    }

    public static  ResponseEntity<ApiResponse> ok(String message) {
        return ResponseEntity.ok(ApiResponse.success(message));
    }

    public static  ResponseEntity<ApiResponse> ok(Object data) {
        return ResponseEntity.ok(ApiResponse.success(data));
    }

    public static  ResponseEntity<ApiResponse> ok(String message, Object data) {
        return ResponseEntity.ok(ApiResponse.success(message, data));
    }

    public static  ResponseEntity<ApiResponse> ok(String message, Object data, Object extra) {
        return ResponseEntity.ok(ApiResponse.success(message, data, extra));
    }

    public static  ResponseEntity<ApiResponse> info(String message) {
        return ResponseEntity.ok(ApiResponse.info(message));
    }

    public static  ResponseEntity<ApiResponse> info(String message, Object data) {
        return ResponseEntity.ok(ApiResponse.info(message, data));
    }

    public static  ResponseEntity<ApiResponse> info(String message, Object data, Object extra) {
        return ResponseEntity.ok(ApiResponse.info(message, data, extra));
    }

    public static  ResponseEntity<ApiResponse> warning(String message) {
        return ResponseEntity.ok(ApiResponse.warning(message));
    }

    public static  ResponseEntity<ApiResponse> warning(String message, Object data) {
        return ResponseEntity.ok(ApiResponse.warning(message, data));
    }

    public static  ResponseEntity<ApiResponse> warning(String message, Object data, Object extra) {
        return ResponseEntity.ok(ApiResponse.warning(message, data, extra));
    }

    public static  ResponseEntity<ApiResponse> error(String message, HttpStatus status) {
        return ResponseEntity
                .status(status)
                .body(ApiResponse.error(message));
    }

    /** Localized {@code message} for the user plus the raw {@code debugMessage} for developers. */
    public static  ResponseEntity<ApiResponse> error(String message, String debugMessage, HttpStatus status) {
        return ResponseEntity
                .status(status)
                .body(ApiResponse.error(message, debugMessage));
    }

    public static  ResponseEntity<ApiResponse> badRequest(String message) {
        return error(message, HttpStatus.BAD_REQUEST);
    }

    public static  ResponseEntity<ApiResponse> badRequest(String message, String debugMessage) {
        return error(message, debugMessage, HttpStatus.BAD_REQUEST);
    }

    public static  ResponseEntity<ApiResponse> unauthorized(String message) {
        return error(message, HttpStatus.UNAUTHORIZED);
    }

    public static  ResponseEntity<ApiResponse> forbidden(String message) {
        return error(message, HttpStatus.FORBIDDEN);
    }

    public static  ResponseEntity<ApiResponse> notFound(String message) {
        return error(message, HttpStatus.NOT_FOUND);
    }

    public static  ResponseEntity<ApiResponse> notFound(String message, String debugMessage) {
        return error(message, debugMessage, HttpStatus.NOT_FOUND);
    }

    public static  ResponseEntity<ApiResponse> conflict(String message) {
        return error(message, HttpStatus.CONFLICT);
    }

    public static  ResponseEntity<ApiResponse> conflict(String message, String debugMessage) {
        return error(message, debugMessage, HttpStatus.CONFLICT);
    }

    public static  ResponseEntity<ApiResponse> internalServerError(String message) {
        return error(message, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    public static  ResponseEntity<ApiResponse> internalServerError(String message, String debugMessage) {
        return error(message, debugMessage, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    public static ResponseEntity<ApiResponse> badGateway(String message) {
        return error(message,HttpStatus.BAD_GATEWAY);
    }

    public static ResponseEntity<ApiResponse> badGateway(String message, String debugMessage) {
        return error(message, debugMessage, HttpStatus.BAD_GATEWAY);
    }
}