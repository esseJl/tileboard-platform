package com.tileboard.app.dto;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

public final class ApiResponses {

    private ApiResponses() {
    }

    public static <T, E> ResponseEntity<ApiResponse<T, E>> ok() {
        return ResponseEntity.ok(ApiResponse.success());
    }

    public static <T, E> ResponseEntity<ApiResponse<T, E>> ok(String message) {
        return ResponseEntity.ok(ApiResponse.success(message));
    }

    public static <T, E> ResponseEntity<ApiResponse<T, E>> ok(T data) {
        return ResponseEntity.ok(ApiResponse.success(data));
    }

    public static <T, E> ResponseEntity<ApiResponse<T, E>> ok(String message, T data) {
        return ResponseEntity.ok(ApiResponse.success(message, data));
    }

    public static <T, E> ResponseEntity<ApiResponse<T, E>> ok(String message, T data, E extra) {
        return ResponseEntity.ok(ApiResponse.success(message, data, extra));
    }

    public static <T, E> ResponseEntity<ApiResponse<T, E>> info(String message) {
        return ResponseEntity.ok(ApiResponse.info(message));
    }

    public static <T, E> ResponseEntity<ApiResponse<T, E>> info(String message, T data) {
        return ResponseEntity.ok(ApiResponse.info(message, data));
    }

    public static <T, E> ResponseEntity<ApiResponse<T, E>> info(String message, T data, E extra) {
        return ResponseEntity.ok(ApiResponse.info(message, data, extra));
    }

    public static <T, E> ResponseEntity<ApiResponse<T, E>> warning(String message) {
        return ResponseEntity.ok(ApiResponse.warning(message));
    }

    public static <T, E> ResponseEntity<ApiResponse<T, E>> warning(String message, T data) {
        return ResponseEntity.ok(ApiResponse.warning(message, data));
    }

    public static <T, E> ResponseEntity<ApiResponse<T, E>> warning(String message, T data, E extra) {
        return ResponseEntity.ok(ApiResponse.warning(message, data, extra));
    }

    public static <T, E> ResponseEntity<ApiResponse<T, E>> error(String message, HttpStatus status) {
        return ResponseEntity
                .status(status)
                .body(ApiResponse.error(message));
    }

    public static <T, E> ResponseEntity<ApiResponse<T, E>> badRequest(String message) {
        return error(message, HttpStatus.BAD_REQUEST);
    }

    public static <T, E> ResponseEntity<ApiResponse<T, E>> unauthorized(String message) {
        return error(message, HttpStatus.UNAUTHORIZED);
    }

    public static <T, E> ResponseEntity<ApiResponse<T, E>> forbidden(String message) {
        return error(message, HttpStatus.FORBIDDEN);
    }

    public static <T, E> ResponseEntity<ApiResponse<T, E>> notFound(String message) {
        return error(message, HttpStatus.NOT_FOUND);
    }

    public static <T, E> ResponseEntity<ApiResponse<T, E>> conflict(String message) {
        return error(message, HttpStatus.CONFLICT);
    }

    public static <T, E> ResponseEntity<ApiResponse<T, E>> internalServerError(String message) {
        return error(message, HttpStatus.INTERNAL_SERVER_ERROR);
    }
}