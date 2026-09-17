package com.tileboard.engine.core;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A thread-safe, type-erased key/value bag that each {@link Game} uses as its
 * mutable runtime state. Games put arbitrary objects in here; built-in
 * features read/write well-known keys. This keeps {@link Game} implementations
 * free of boilerplate fields while still being testable in isolation.
 *
 * <p>All public methods are {@code synchronized} on the instance itself so a
 * game loop thread and a callback executor thread can both access state safely.
 */
public final class GameState {

    private final Map<String, Object> store = new HashMap<>();

    /** Stores {@code value} under {@code key}, replacing any previous value. */
    public synchronized <T> void put(String key, T value) {
        Objects.requireNonNull(key, "key");
        store.put(key, value);
    }

    /**
     * Returns the value stored under {@code key} cast to {@code type}.
     *
     * @throws ClassCastException if the stored value is not assignable to {@code type}
     */
    @SuppressWarnings("unchecked")
    public synchronized <T> Optional<T> get(String key, Class<T> type) {
        Object v = store.get(key);
        if (v == null) return Optional.empty();
        return Optional.of(type.cast(v));
    }

    /** Returns the value or {@code defaultValue} if absent. */
    public synchronized <T> T getOrDefault(String key, Class<T> type, T defaultValue) {
        return get(key, type).orElse(defaultValue);
    }

    public synchronized boolean containsKey(String key) {
        return store.containsKey(key);
    }

    public synchronized void remove(String key) {
        store.remove(key);
    }

    /** Clears all stored entries. */
    public synchronized void clear() {
        store.clear();
    }

    /** Returns an unmodifiable snapshot of the current state map (for SSE / debugging). */
    public synchronized Map<String, Object> snapshot() {
        return Collections.unmodifiableMap(new HashMap<>(store));
    }
}