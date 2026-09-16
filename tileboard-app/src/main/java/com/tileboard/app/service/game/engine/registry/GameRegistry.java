package com.tileboard.app.service.game.engine.registry;

import com.tileboard.app.exception.GameNotFoundException;
import com.tileboard.app.service.game.engine.core.GameDefinition;
import com.tileboard.app.service.game.engine.core.GameFactory;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Discovers all {@link GameFactory} beans and provides lookup by id.
 * No manual registration list – just add a {@code @Component} factory.
 */
@Component
public class GameRegistry {

    private final Map<String, GameFactory> byId;

    public GameRegistry(List<GameFactory> factories) {
        this.byId = factories.stream()
                .collect(Collectors.toUnmodifiableMap(
                        GameFactory::gameId,
                        Function.identity(),
                        (a, b) -> {
                            throw new IllegalStateException(
                                    "Duplicate gameId '" + a.gameId() + "' from "
                                            + a.getClass().getName() + " and " + b.getClass().getName());
                        }
                ));
    }

    public GameFactory require(String gameId) {
        GameFactory f = byId.get(gameId);
        if (f == null) {
            throw new GameNotFoundException(gameId);
        }
        return f;
    }

    public Collection<GameDefinition> listDefinitions() {
        return byId.values().stream()
                .map(GameFactory::definition)
                .toList();
    }

    public boolean contains(String gameId) {
        return byId.containsKey(gameId);
    }
}
