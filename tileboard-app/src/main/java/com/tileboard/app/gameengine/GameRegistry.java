package com.tileboard.app.gameengine;

import com.tileboard.app.common.exception.GameNotFoundException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Catalog of every {@link GameFactory} Spring knows about, keyed by
 * {@link GameFactory#gameId()}.
 *
 * <p>This is the open/closed replacement for the old central switch
 * statement: this class has no knowledge of any specific game and never
 * needs to change when one is added, removed, or renamed. Spring supplies
 * the full list of factories via constructor injection - each game
 * registers itself simply by existing as a {@code @Component}.
 */
@Component
public class GameRegistry {

    private final Map<String, GameFactory> factoriesById;

    public GameRegistry(List<GameFactory> factories) {
        this.factoriesById = factories.stream()
                .collect(Collectors.toUnmodifiableMap(GameFactory::gameId, factory -> factory));
    }

    public List<GameDefinition> listDefinitions() {
        return factoriesById.values().stream()
                .map(GameFactory::definition)
                .toList();
    }

    /** @throws GameNotFoundException if no factory is registered under {@code gameId} */
    public GameFactory getFactory(String gameId) {
        GameFactory factory = factoriesById.get(gameId);
        if (factory == null) {
            throw new GameNotFoundException(gameId);
        }
        return factory;
    }
}
