package com.tileboard.engine.spring;

import com.tileboard.engine.core.DefaultGameRegistry;
import com.tileboard.engine.core.Game;
import com.tileboard.engine.core.GameRegistry;
import com.tileboard.engine.event.GameEventBus;
import com.tileboard.engine.event.GameEventBusImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.List;

/**
 * Spring Boot auto-configuration for the Tileboard game engine.
 *
 * <p>Simply adding this library to the classpath is enough to get a
 * {@link GameRegistry} (auto-populated from every {@link Game} bean found in
 * the application context) and a {@link GameEventBus}, both usable
 * independently of any hardware connection.
 *
 * <p>The {@link com.tileboard.engine.core.GameEngine} itself is
 * <strong>not</strong> created eagerly here, because it needs an already-open
 * {@link com.tileboard.serial.gateway.TileGatewayClient} and this library has
 * no opinion on serial ports, baud rates or board geometry - that is entirely
 * the application's job. Instead this configuration registers a
 * {@link GameEngineManager}, which listens for {@link GatewayConnectedEvent} /
 * {@link GatewayDisconnectedEvent} and (re)binds the engine whenever the
 * application actually opens or closes its connection to the board -
 * typically from the very same REST endpoints already used to list, assign
 * and connect serial ports. Controllers/services that need to start or stop
 * games should depend on {@link GameEngineManager}, not construct a
 * {@link com.tileboard.engine.core.GameEngine} themselves.
 *
 * <p>This class is registered under
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports},
 * so it is picked up automatically by any Spring Boot application that has
 * this jar on its classpath - no manual {@code @Import} is required.
 *
 * <h3>Minimal Spring Boot application</h3>
 * <pre>{@code
 * @SpringBootApplication
 * public class MyApp {
 *     public static void main(String[] args) { SpringApplication.run(MyApp.class, args); }
 * }
 * }</pre>
 *
 * <h3>application.yml</h3>
 * <pre>
 * tileboard:
 *   engine:
 *     tick-interval: 100ms
 * </pre>
 *
 * <p>Override any bean below with your own {@code @Bean} of the same type to
 * customise defaults - every bean here is {@code @ConditionalOnMissingBean}.
 */
@AutoConfiguration
@EnableConfigurationProperties(TileboardEngineProperties.class)
public class TileboardEngineAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(TileboardEngineAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public GameEventBus gameEventBus() {
        return new GameEventBusImpl();
    }

    @Bean
    @ConditionalOnMissingBean
    public GameRegistry gameRegistry(
            @Autowired(required = false) List<Game> games) {
        GameRegistry registry = new DefaultGameRegistry();
        if (games == null || games.isEmpty()) {
            log.warn("No Game beans found in context — registry is empty. "
                    + "Register at least one @Bean implementing Game.");
        } else {
            games.forEach(game -> {
                registry.register(game);
                log.info("Auto-registered game: '{}' ({})",
                        game.descriptor().displayName(),
                        game.descriptor().gameId());
            });
        }
        return registry;
    }

    @Bean
    @ConditionalOnMissingBean
    public GameEngineManager gameEngineManager(
            GameRegistry registry, GameEventBus eventBus, TileboardEngineProperties props) {
        return new GameEngineManager(registry, eventBus, props.getTickInterval());
    }
}
