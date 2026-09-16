package com.tileboard.gamekit.validation;

import java.util.Objects;

public final class GameArguments {
    private GameArguments() {}

    public static String requiredText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }

    public static <T> T required(T value, String name) {
        return Objects.requireNonNull(value, name);
    }

    public static int positive(int value, String name) {
        if (value < 1) throw new IllegalArgumentException(name + " must be >= 1");
        return value;
    }
}
