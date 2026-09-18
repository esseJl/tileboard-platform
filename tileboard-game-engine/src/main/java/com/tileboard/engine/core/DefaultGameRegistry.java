package com.tileboard.engine.core;

import com.tileboard.engine.exception.GameNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe, in-memory implementation of {@link GameRegistry}.
 */
public final class DefaultGameRegistry implements GameRegistry {

    private static final Logger log = LoggerFactory.getLogger(DefaultGameRegistry.class);

    // descriptor + factory stored together
    private record Entry(GameDescriptor descriptor, GameFactory factory) {}

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    @Override
    public void register(Game game) {
        Objects.requireNonNull(game, "game");
        register(game.descriptor(), () -> game);
        log.info("Registered game '{}' ({})", game.descriptor().displayName(), game.descriptor().gameId());
    }

    @Override
    public void register(GameDescriptor descriptor, GameFactory factory) {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(factory,    "factory");
        entries.put(descriptor.gameId(), new Entry(descriptor, factory));
        log.info("Registered game factory for '{}'", descriptor.gameId());
    }

    @Override
    public Optional<GameDescriptor> find(String gameId) {
        return Optional.ofNullable(entries.get(gameId)).map(Entry::descriptor);
    }

    @Override
    public List<GameDescriptor> listAll() {
        return entries.values().stream()
                .map(Entry::descriptor)
                .sorted(Comparator.comparing(GameDescriptor::displayName))
                .toList();
    }

    @Override
    public Game instantiate(String gameId) {
        Entry e = entries.get(gameId);
        if (e == null) throw new GameNotFoundException(gameId);
        return e.factory().create();
    }

    @Override
    public boolean isRegistered(String gameId) {
        return entries.containsKey(gameId);
    }
}