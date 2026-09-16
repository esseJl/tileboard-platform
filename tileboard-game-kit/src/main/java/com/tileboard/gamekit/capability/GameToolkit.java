package com.tileboard.gamekit.capability;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * The engine's extensibility mechanism ("بتوانیم در آینده قابلیت و
 * امکانات جدید در موتور اضافه کنیم"): a type-safe, per-session container
 * that holds exactly the capabilities ({@link com.tileboard.gamekit.touch.TouchTracker},
 * {@link com.tileboard.gamekit.state.ScoreBoard}, a brand-new one a future
 * game needs, ...) a given game actually uses.
 *
 * <p>Every capability in this kit is a plain, independent class - nothing
 * about it requires being "known" to any registry. {@code GameToolkit}
 * exists only as a convenience so a game can build up its own bag of
 * capabilities once in {@code start()} and pass a single object around
 * instead of a dozen constructor parameters, and so a capability built by
 * one part of a game (a shared {@code TouchTracker}) is easy to find from
 * another part of the same game. Adding a brand-new kind of capability to
 * the platform is therefore purely additive: write the class (anywhere -
 * in this kit or in a specific game's own package), {@link #register} an
 * instance of it, {@link #get} it back by type wherever it's needed. No
 * existing class here is ever touched.
 *
 * <p>Thread-safe: backed by {@link ConcurrentHashMap}. Registration
 * typically happens once, single-threaded, during a game's {@code start()};
 * lookups may then happen concurrently from a touch-input thread and a
 * clock-tick thread.
 */
public final class GameToolkit {

    private final Map<Class<?>, Object> capabilities = new ConcurrentHashMap<>();

    /** Registers {@code instance} as the toolkit's instance of {@code type}, replacing any previous one. Returns {@code instance} for fluent chaining. */
    public <C> C register(Class<C> type, C instance) {
        capabilities.put(type, instance);
        return instance;
    }

    /** @throws IllegalStateException if no instance of {@code type} was ever {@link #register}ed */
    public <C> C get(Class<C> type) {
        return find(type).orElseThrow(() -> new IllegalStateException(
                "No capability registered for " + type.getName() + " - register one in start() before using it"));
    }

    public <C> Optional<C> find(Class<C> type) {
        return Optional.ofNullable(type.cast(capabilities.get(type)));
    }

    /** Returns the already-registered instance of {@code type}, or builds one with {@code factory}, registers it, and returns it. */
    @SuppressWarnings("unchecked")
    public <C> C getOrCreate(Class<C> type, Supplier<C> factory) {
        return (C) capabilities.computeIfAbsent(type, ignored -> factory.get());
    }
}
