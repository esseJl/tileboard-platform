package com.tileboard.app.dto;


public record ApiResponse(Status status, String message, Object data, Object extra) {


    public static  ApiResponse success() {
        return new ApiResponse(Status.SUCCESS, null, null, null);
    }

    public static  ApiResponse success(String message) {
        return new ApiResponse(Status.SUCCESS, message, null, null);
    }

    public static  ApiResponse success(Object data) {
        return new ApiResponse(Status.SUCCESS, null, data, null);
    }

    public static  ApiResponse success(String message, Object data) {
        return new ApiResponse(Status.SUCCESS, message, data, null);
    }

    public static  ApiResponse success(String message, Object data, Object extra) {
        return new ApiResponse(Status.SUCCESS, message, data, extra);
    }

    public static  ApiResponse info(String message) {
        return new ApiResponse(Status.INFO, message, null, null);
    }

    public static  ApiResponse info(String message, Object data) {
        return new ApiResponse(Status.INFO, message, data, null);
    }

    public static  ApiResponse info(String message, Object data, Object extra) {
        return new ApiResponse(Status.INFO, message, data, extra);
    }

    public static  ApiResponse warning(String message) {
        return new ApiResponse(Status.WARNING, message, null, null);
    }

    public static  ApiResponse warning(String message, Object data) {
        return new ApiResponse(Status.WARNING, message, data, null);
    }

    public static  ApiResponse warning(String message, Object data, Object extra) {
        return new ApiResponse(Status.WARNING, message, data, extra);
    }


    public static  ApiResponse error(String message) {
        return new ApiResponse(Status.ERROR, message, null, null);
    }
}