package com.tileboard.app.dto;


public record ApiResponse<T, E>(Status status, String message, T data, E extra) {


    public static <T, E> ApiResponse<T, E> success() {
        return new ApiResponse<>(Status.SUCCESS, null, null, null);
    }

    public static <T, E> ApiResponse<T, E> success(String message) {
        return new ApiResponse<>(Status.SUCCESS, message, null, null);
    }

    public static <T, E> ApiResponse<T, E> success(T data) {
        return new ApiResponse<>(Status.SUCCESS, null, data, null);
    }

    public static <T, E> ApiResponse<T, E> success(String message, T data) {
        return new ApiResponse<>(Status.SUCCESS, message, data, null);
    }

    public static <T, E> ApiResponse<T, E> success(String message, T data, E extra) {
        return new ApiResponse<>(Status.SUCCESS, message, data, extra);
    }

    public static <T, E> ApiResponse<T, E> info(String message) {
        return new ApiResponse<>(Status.INFO, message, null, null);
    }

    public static <T, E> ApiResponse<T, E> info(String message, T data) {
        return new ApiResponse<>(Status.INFO, message, data, null);
    }

    public static <T, E> ApiResponse<T, E> info(String message, T data, E extra) {
        return new ApiResponse<>(Status.INFO, message, data, extra);
    }

    public static <T, E> ApiResponse<T, E> warning(String message) {
        return new ApiResponse<>(Status.WARNING, message, null, null);
    }

    public static <T, E> ApiResponse<T, E> warning(String message, T data) {
        return new ApiResponse<>(Status.WARNING, message, data, null);
    }

    public static <T, E> ApiResponse<T, E> warning(String message, T data, E extra) {
        return new ApiResponse<>(Status.WARNING, message, data, extra);
    }


    public static <T, E> ApiResponse<T, E> error(String message) {
        return new ApiResponse<>(Status.ERROR, message, null, null);
    }
}