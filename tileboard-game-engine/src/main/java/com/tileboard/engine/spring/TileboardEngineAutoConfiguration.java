package com.tileboard.engine.spring;

import com.tileboard.engine.core.DefaultGameRegistry;
import com.tileboard.engine.core.Game;
import com.tileboard.engine.core.GameRegistry;
import com.tileboard.engine.event.EventOverflowPolicy;
import com.tileboard.engine.event.GameEventBus;
import com.tileboard.engine.event.GameEventBusImpl;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.DependsOn;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

@AutoConfiguration
@EnableConfigurationProperties(TileboardEngineProperties.class)
public class TileboardEngineAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(TileboardEngineAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public GameEventBusImpl gameEventBus(TileboardEngineProperties props) {
        return new GameEventBusImpl(props.getEventBusQueueCapacity(), EventOverflowPolicy.DROP_OLDEST);
    }

    @Bean
    @ConditionalOnMissingBean
    public GameRegistry gameRegistry(@Autowired(required = false) List<Game> games) {
        GameRegistry registry = new DefaultGameRegistry();
        if (games == null || games.isEmpty()) {
            log.warn("No Game beans found in context - registry is empty. "
                    + "Register at least one @Bean implementing Game.");
        } else {
            games.forEach(game -> {
                registry.register(game);
                log.info("Auto-registered game: '{}' ({})",
                        game.descriptor().displayName(), game.descriptor().gameId());
            });
        }
        return registry;
    }

    @Bean
    @ConditionalOnMissingBean
    @DependsOn("gameEventBus")
    public GameEngineManager gameEngineManager(GameRegistry registry, GameEventBus eventBus,
                                               TileboardEngineProperties props) {
        return new GameEngineManager(registry, eventBus, props);
    }

    @Bean
    @ConditionalOnMissingBean
    public SseGameEventPublisher sseGameEventPublisher(
            GameEventBus eventBus, ScheduledExecutorService tileboardSseHeartbeatScheduler) {
        return new SseGameEventPublisher(eventBus, tileboardSseHeartbeatScheduler);
    }

    @Bean
    @ConditionalOnBean(MeterRegistry.class)
    public GameEngineMetricsBinder gameEngineMetricsBinder(
            MeterRegistry registry, GameEngineManager manager, GameEventBusImpl bus) {
        return new GameEngineMetricsBinder(registry, manager, bus);
    }

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean(name = "tileboardSseHeartbeatScheduler")
    public ScheduledExecutorService tileboardSseHeartbeatScheduler() {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "tileboard-sse-heartbeat");
            t.setDaemon(true);
            return t;
        });
    }
    
}