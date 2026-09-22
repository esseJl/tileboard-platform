package com.tileboard.app.game;

import com.tileboard.app.config.DeviceConfiguration;
import com.tileboard.app.service.device.DeviceConfigurationService;
import com.tileboard.engine.core.Game;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ثبت بازی‌های نمونه به عنوان Spring Bean
 *
 * <p>
 * هر Bean از نوع {@link Game} توسط {@link com.tileboard.engine.spring.TileboardEngineAutoConfiguration#gameRegistry}
 * به صورت خودکار در {@link com.tileboard.engine.core.GameRegistry} ثبت می‌شود (auto-registration).
 * این یعنی کافی است بازی را به عنوان Bean تعریف کنیم تا در لیست بازی‌ها ({@code GET /api/v1/games}) ظاهر شود
 * و قابل شروع باشد ({@code POST /api/v1/games/sessions}).
 * </p>
 *
 * <h2>نکات concurrency و lifecycle</h2>
 * <ul>
 *   <li>این کلاس یک بار در startup توسط Spring ساخته می‌شود (singleton).</li>
 *   <li>متد sequentialTouchGame() یک instance از بازی می‌سازد که طبق قرارداد Game باید stateless باشد
 *       و در تمام session ها reuse می‌شود (مشابه Servlet singleton).</li>
 *   <li>اگر بازی نیاز به state در سطح instance داشته باشد، باید با GameFactory ثبت شود، نه به صورت singleton Bean.</li>
 *   <li>ما سایز برد را از DeviceConfigurationService می‌خوانیم تا بازی با برد متصل هماهنگ باشد.
 *       اگر هنوز دستگاه کانفیگ نشده، پیش‌فرض 8x8 استفاده می‌شود.</li>
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
        // سعی کن سایز برد فعلی را از کانفیگ بخوانی
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

        // بازی نمونه آموزشی که شامل تمام انیمیشن‌های درخواستی است:
        // - countdown قبل از شروع
        // - standby در ابتدا
        // - win (radial burst) در پایان موفق
        // - lose (fade to red / descending curtain) در لمس اشتباه یا timeout
        return new SequentialTouchGame(width, height);
    }

    /**
     * مثال دوم: نسخه کوچک 4x4 برای تست سریع روی برد کوچک یا شبیه‌ساز
     * اگر بخواهی این Bean را فعال کنی، کافی است کامنت @Bean را برداری.
     * توجه: gameId باید یکتا باشد، پس باید در سازنده SequentialTouchGame یک gameId متفاوت بدهی
     * یا یک کلاس جدا بسازی.
     */
    // @Bean
    // public Game sequentialTouchGame4x4() {
    //     return new SequentialTouchGame(4, 4) {
    //         @Override
    //         public com.tileboard.engine.core.GameDescriptor descriptor() {
    //             return com.tileboard.engine.core.GameDescriptor.builder("sequential-touch-4x4", "Sequential Touch 4x4 (Test)")
    //                     .category("TUTORIAL")
    //                     .description("نسخه 4x4 برای تست سریع - 16 تایل به ترتیب روشن می‌شوند")
    //                     .boardSize(4, 4)
    //                     .players(1, 1)
    //                     .build();
    //         }
    //     };
    // }
}
