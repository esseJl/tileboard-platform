package com.tileboard.app.game;

import com.tileboard.app.config.DeviceConfiguration;
import com.tileboard.app.service.device.DeviceConfigurationService;
import com.tileboard.engine.core.Game;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Register sample games as Spring Beans
 *
 * <p>
 * Every Bean of type {@link Game} is auto-registered into {@link com.tileboard.engine.core.GameRegistry}
 * by {@link com.tileboard.engine.spring.TileboardEngineAutoConfiguration#gameRegistry}.
 * That means defining a game as a Bean is enough for it to appear in game list
 * ({@code GET /api/v1/games}) and be startable ({@code POST /api/v1/games/sessions}).
 * </p>
 *
 * <h2>Concurrency and Lifecycle Notes</h2>
 * <ul>
 *   <li>This class is created once at startup by Spring (singleton).</li>
 *   <li>Method sequentialTouchGame() creates a game instance that per Game contract must be stateless
 *       and is reused across all sessions (similar to Servlet singleton).</li>
 *   <li>If a game needs per-instance construction state, it should be registered with GameFactory, not as singleton Bean.</li>
 *   <li>We read board size from DeviceConfigurationService so game matches connected board.
 *       If device not yet configured, default 8x8 is used.</li>
 * </ul>
 */
@Configuration
public class GameBeansConfig {

    private static final Logger log = LoggerFactory.getLogger(GameBeansConfig.class);

    private final DeviceConfigurationService deviceConfigService;

    public GameBeansConfig(DeviceConfigurationService deviceConfigService) {
        this.deviceConfigService = deviceConfigService;
    }

    @Bean
    public Game sequentialTouchGame() {
        // Try to read current board size from config
        int width = 8;
        int height = 8;

        var current = deviceConfigService.current();
        if (current.isPresent()) {
            DeviceConfiguration cfg = current.get();
            width = cfg.width();
            height = cfg.height();
            log.info("Creating SequentialTouchGame with device size {}x{} from current config", width, height);
        } else {
            log.info("No device config yet, creating SequentialTouchGame with default {}x{}", width, height);
        }

        // Tutorial sample game containing all requested animations:
        // - countdown before start
        // - standby at beginning
        // - win (radial burst) on successful finish
        // - lose (fade to red / descending curtain) on wrong touch or timeout
        return new SequentialTouchGame(width, height);
    }

    /**
     * Second example: small 4x4 version for quick testing on small board or simulator
     * To enable this Bean, uncomment @Bean annotation.
     * Note: gameId must be unique, so you need to give different gameId in SequentialTouchGame constructor
     * or create a separate class.
     */
    // @Bean
    // public Game sequentialTouchGame4x4() {
    //     return new SequentialTouchGame(4, 4) {
    //         @Override
    //         public com.tileboard.engine.core.GameDescriptor descriptor() {
    //             return com.tileboard.engine.core.GameDescriptor.builder("sequential-touch-4x4", "Sequential Touch 4x4 (Test)")
    //                     .category("TUTORIAL")
    //                     .description("4x4 version for quick testing - 16 tiles light up sequentially")
    //                     .boardSize(4, 4)
    //                     .players(1, 1)
    //                     .build();
    //         }
    //     };
    // }
}
