package com.tileboard.app.dto;

/**
 * @param message      user-facing message, localized (Persian) for display in the frontend.
 * @param debugMessage raw, untranslated diagnostic text (English) meant for developers/debugging
 *                      - logs, browser dev tools, bug reports. Never shown directly to end users.
 *                      Null for plain success responses that carry no message at all.
 */
public record ApiResponse(Status status, String message, Object data, Object extra, String debugMessage) {

    public static ApiResponse success() {
        return new ApiResponse(Status.SUCCESS, null, null, null, null);
    }

    public static ApiResponse success(String message) {
        return new ApiResponse(Status.SUCCESS, message, null, null, null);
    }

    public static ApiResponse success(Object data) {
        return new ApiResponse(Status.SUCCESS, null, data, null, null);
    }

    public static ApiResponse success(String message, Object data) {
        return new ApiResponse(Status.SUCCESS, message, data, null, null);
    }

    public static ApiResponse success(String message, Object data, Object extra) {
        return new ApiResponse(Status.SUCCESS, message, data, extra, null);
    }

    public static ApiResponse info(String message) {
        return new ApiResponse(Status.INFO, message, null, null, null);
    }

    public static ApiResponse info(String message, Object data) {
        return new ApiResponse(Status.INFO, message, data, null, null);
    }

    public static ApiResponse info(String message, Object data, Object extra) {
        return new ApiResponse(Status.INFO, message, data, extra, null);
    }

    public static ApiResponse warning(String message) {
        return new ApiResponse(Status.WARNING, message, null, null, null);
    }

    public static ApiResponse warning(String message, Object data) {
        return new ApiResponse(Status.WARNING, message, data, null, null);
    }

    public static ApiResponse warning(String message, Object data, Object extra) {
        return new ApiResponse(Status.WARNING, message, data, extra, null);
    }

    /** Plain error with no separate debug message (e.g. framework-generated text that's already raw). */
    public static ApiResponse error(String message) {
        return new ApiResponse(Status.ERROR, message, null, null, message);
    }

    /** Error with a localized {@code message} for the user and a raw {@code debugMessage} for developers. */
    public static ApiResponse error(String message, String debugMessage) {
        return new ApiResponse(Status.ERROR, message, null, null, debugMessage);
    }
}
