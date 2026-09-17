package com.tileboard.engine.spring;

import com.tileboard.engine.core.*;
import com.tileboard.engine.event.GameEventBus;
import com.tileboard.engine.event.GameEventBusImpl;
import com.tileboard.serial.gateway.TileGatewayClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Spring Boot auto-configuration for the Tileboard game engine.
 *
 * <p>Simply adding this library to the classpath and annotating the main
 * application class (or any {@code @Configuration}) with
 * {@code @EnableConfigurationProperties} is enough to wire everything up.
 * Any {@link Game} bean found in the application context is automatically
 * registered in the {@link GameRegistry}.
 *
 * <p>Override any bean with a {@code @Bean} of the same type in your own
 * {@code @Configuration} to customise defaults.
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
 *     serial-port: COM3
 *     board-width: 8
 *     board-height: 8
 *     tick-interval: 100ms
 * </pre>
 */
@Configuration
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
    public TileGatewayClient tileGatewayClient(TileboardEngineProperties props) {
        return TileboardGatewayAdapter.create(props);
    }

    @Bean
    @ConditionalOnMissingBean
    public GameRegistry gameRegistry() {
        return new DefaultGameRegistry();
    }

    @Bean
    @ConditionalOnMissingBean
    public GameEngine gameEngine(
            GameRegistry registry,
            TileGatewayClient gateway,
            GameEventBus eventBus,
            TileboardEngineProperties props,
            // Auto-wires all Game beans from the application context
            @Autowired(required = false) List<Game> games
    ) {
        if (games != null) {
            games.forEach(game -> {
                registry.register(game);
                log.info("Auto-registered game: '{}' ({})",
                        game.descriptor().displayName(), game.descriptor().gameId());
            });
        }
        return new GameEngineImpl(registry, gateway, eventBus, props.getTickInterval());
    }
}