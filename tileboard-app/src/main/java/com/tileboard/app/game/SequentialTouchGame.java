package com.tileboard.app.game;

import com.tileboard.engine.core.Game;
import com.tileboard.engine.core.GameContext;
import com.tileboard.engine.core.GameDescriptor;
import com.tileboard.engine.core.GameResult;
import com.tileboard.engine.feature.AnimationSystem;
import com.tileboard.engine.model.TileColor;
import com.tileboard.engine.model.TileEvent;
import com.tileboard.serial.board.Board;
import com.tileboard.serial.board.Position;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * بازی نمونه آموزشی: لمس ترتیبی تایل‌ها
 *
 * <h2>سناریو بازی</h2>
 * <ol>
 *   <li>برد خاموش می‌شود، انیمیشن standby (BREATHING) به مدت 2 ثانیه نمایش داده می‌شود</li>
 *   <li>انیمیشن countdown (3 → 2 → 1) اجرا می‌شود</li>
 *   <li>تمام تایل‌های برد در لیستی row-major ذخیره می‌شوند (0,0) → (0,1) ...</li>
 *   <li>تایل جاری با رنگی از پالت روشن می‌شود</li>
 *   <li>بازیکن باید دقیقا همان تایل را لمس کند:
 *       <ul>
 *         <li>لمس درست → +10 امتیاز، تایل خاموش، نوبت تایل بعدی</li>
 *         <li>لمس اشتباه → انیمیشن lose کوتاه (FADE_TO_RED)، امتیاز کم نمی‌شود اما تایل جاری دوباره روشن می‌شود</li>
 *       </ul>
 *   </li>
 *   <li>وقتی همه تایل‌ها لمس شدند، انیمیشن win (RADIAL_BURST) و سپس پایان بازی با برد</li>
 *   <li>اگر بازیکن در 60 ثانیه تمام نکند، انیمیشن lose (DESCENDING_CURTAIN) و باخت</li>
 * </ol>
 *
 * <h2>نکات thread-safe و concurrency</h2>
 * <p>
 * این بازی از قرارداد stateless بودن {@link Game} پیروی می‌کند:
 * هیچ فیلد mutable در خود کلاس نگه نمی‌دارد. تمام وضعیت جلسه (ایندکس جاری، لیست موقعیت‌ها)
 * داخل {@link GameContext#state()} ذخیره می‌شود که خودش synchronized است (HashMap با synchronized methods).
 * </p>
 * <p>
 * دسترسی به برد از طریق {@link com.tileboard.engine.core.BoardChannel} انجام می‌شود که:
 * <ul>
 *   <li>از {@link java.util.concurrent.locks.ReentrantLock} برای محافظت از بافر داخلی استفاده می‌کند</li>
 *   <li>از یک Object جداگانه به نام gatewayWriteLock برای سریالایز کردن write ها روی سیم استفاده می‌کند</li>
 *   <li>حتی اگر چند thread همزمان setTile کنند، آخرین snapshot سازگار ارسال می‌شود (coalescing semantics)</li>
 * </ul>
 * </p>
 * <p>
 * انیمیشن‌ها در {@link AnimationSystem} روی یک SingleThreadExecutor اجرا می‌شوند.
 * هر انیمیشن جدید با increment کردن AtomicLong generation انیمیشن قبلی را کنسل می‌کند (cooperative cancellation).
 * RunToken.isCancelled() قبل از هر sleep/show چک می‌شود و در صورت کنسل شدن AnimationCancelledException پرتاب می‌کند.
 * </p>
 */
public class SequentialTouchGame implements Game {

    private static final Logger log = LoggerFactory.getLogger(SequentialTouchGame.class);

    // کلیدهای ذخیره شده در GameState (thread-safe bag)
    private static final String KEY_POSITIONS = "sequential.positions";
    private static final String KEY_INDEX = "sequential.index";
    private static final String KEY_TOTAL = "sequential.total";

    // پالت رنگی برای روشن کردن ترتیبی تایل‌ها
    private static final TileColor[] PALETTE = {
            TileColor.RED, TileColor.GREEN, TileColor.BLUE,
            TileColor.YELLOW, TileColor.PINK, TileColor.LIGHT_BLUE, TileColor.WHITE
    };

    private final GameDescriptor descriptor;

    public SequentialTouchGame() {
        // این بازی روی هر سایز برد کار می‌کند اما برای سادگی 4x4 یا 8x8 توصیه می‌شود
        // requiredWidth/Height باید با DeviceConfiguration مطابقت داشته باشد
        // در غیر این صورت GameEngineImpl.validateBoardSize خطا می‌دهد
        this.descriptor = GameDescriptor.builder("sequential-touch", "Sequential Touch Challenge")
                .category("TUTORIAL")
                .description("به ترتیب هر تایل روشن می‌شود؛ با لمس آن امتیاز بگیر و به تایل بعدی برو. شامل countdown، standby، win و lose انیمیشن.")
                .boardSize(8, 8) // پیش‌فرض 8x8، قابل تغییر در صورت نیاز
                .players(1, 1)
                .build();
    }

    public SequentialTouchGame(int width, int height) {
        this.descriptor = GameDescriptor.builder("sequential-touch", "Sequential Touch Challenge")
                .category("TUTORIAL")
                .description("به ترتیب هر تایل روشن می‌شود؛ با لمس آن امتیاز بگیر و به تایل بعدی برو.")
                .boardSize(width, height)
                .players(1, 1)
                .build();
    }

    @Override
    public GameDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public void onStart(GameContext ctx) {
        log.info("[{}] SequentialTouchGame onStart - board {}x{}", ctx.sessionId(), ctx.boardWidth(), ctx.boardHeight());

        // 1. برد را خاموش کن و امتیازها را ریست کن
        ctx.fillBoard(TileColor.OFF);
        ctx.scores().resetAll();
        ctx.state().clear();

        // 2. انیمیشن standby: حالت انتظار قبل از شروع
        // این انیمیشن بی‌نهایت اجرا می‌شود تا cancel شود، پس ما آن را 2 ثانیه اجرا و سپس cancel می‌کنیم
        // پیاده‌سازی AnimationSystem: cancelCurrent() generation را increment می‌کند و Future قبلی را cancel می‌کند
        try {
            log.info("[{}] Playing STANDBY (BREATHING) for 2 seconds...", ctx.sessionId());
            ctx.animations().playStandbyAnimation(AnimationSystem.StandbyAnimationType.BREATHING)
                    .get(2, TimeUnit.SECONDS);
        } catch (Exception e) {
            // اگر timeout شد، یعنی انیمیشن هنوز در حال اجراست (چون بی‌نهایت است) - آن را cancel می‌کنیم
            ctx.animations().cancelCurrent();
            log.info("[{}] Standby cancelled, moving to countdown", ctx.sessionId());
        }

        // 3. انیمیشن countdown: 3 → 2 → 1 → چشمک سبز
        // playCountdown داخل یک SingleThreadExecutor اجرا می‌شود و تا پایان countdown بلاک نمی‌کند مگر join کنیم
        // ما join می‌کنیم تا بازیکن آماده شود
        try {
            log.info("[{}] Playing COUNTDOWN...", ctx.sessionId());
            ctx.animations().playCountdown(700).join(); // هر رقم 700ms
        } catch (Exception e) {
            log.warn("[{}] Countdown interrupted", ctx.sessionId(), e);
        }

        // 4. لیست تمام موقعیت‌ها به ترتیب row-major
        List<Position> allPositions = new ArrayList<>();
        for (int r = 0; r < ctx.boardHeight(); r++) {
            for (int c = 0; c < ctx.boardWidth(); c++) {
                allPositions.add(new Position(r, c));
            }
        }

        ctx.state().put(KEY_POSITIONS, allPositions);
        ctx.state().put(KEY_INDEX, 0);
        ctx.state().put(KEY_TOTAL, allPositions.size());

        // 5. تایمر کلی بازی: اگر در 90 ثانیه تمام نشد، باخت
        // GameTimer از AtomicReference<Runnable> برای onExpire و volatile Instant برای زمان‌ها استفاده می‌کند
        // checkExpiry() هر tick (100ms) توسط GameSessionImpl.runTick() صدا زده می‌شود
        ctx.timer().startCountdown(Duration.ofSeconds(90), () -> {
            log.info("[{}] Timer expired - player LOST", ctx.sessionId());
            // انیمیشن lose و سپس باخت
            // این callback روی tick thread اجرا می‌شود، پس نباید بلاک طولانی کنیم
            // انیمیشن را async شروع می‌کنیم و بعد loseSession
            ctx.animations().playLoseAnimation(AnimationSystem.LoseAnimationType.DESCENDING_CURTAIN)
                    .thenRun(() -> ctx.loseSession());
        });

        // 6. اولین تایل را روشن کن
        lightCurrentTile(ctx);

        log.info("[{}] Game started with {} tiles", ctx.sessionId(), allPositions.size());
    }

    @Override
    public void onTileEvent(GameContext ctx, TileEvent event) {
        // این متد فقط وقتی GameStatus=RUNNING است توسط GameSessionImpl.handleTileEvent صدا زده می‌شود
        // handleTileEvent خودش touchHistory و reactionSpeed را record می‌کند قبل از صدا زدن ما

        // وضعیت را از GameState بخوان (thread-safe: synchronized methods)
        @SuppressWarnings("unchecked")
        List<Position> positions = ctx.state().get(KEY_POSITIONS, List.class).orElse(List.of());
        int currentIndex = ctx.state().getOrDefault(KEY_INDEX, Integer.class, 0);

        if (positions.isEmpty() || currentIndex >= positions.size()) {
            // بازی قبلا تمام شده
            return;
        }

        Position expected = positions.get(currentIndex);
        Position touched = event.position();

        log.debug("[{}] Touch at {} - expected {}", ctx.sessionId(), touched, expected);

        if (touched.equals(expected)) {
            // لمس درست
            handleCorrectTouch(ctx, currentIndex, positions);
        } else {
            // لمس اشتباه: انیمیشن lose کوتاه و سپس برگشت
            handleWrongTouch(ctx);
        }
    }

    private void handleCorrectTouch(GameContext ctx, int currentIndex, List<Position> positions) {
        String playerId = ctx.players().get(0).id();

        // امتیاز اضافه کن - ScoreSystem از ConcurrentHashMap<String, AtomicInteger> استفاده می‌کند
        // add() با AtomicInteger.addAndGet thread-safe است
        int newScore = ctx.scores().add(playerId, 10);
        log.info("[{}] Correct! Tile {}/{} touched, score={}", ctx.sessionId(), currentIndex + 1, positions.size(), newScore);

        // تایل فعلی را خاموش کن
        Position justTouched = positions.get(currentIndex);
        ctx.setTile(justTouched.row(), justTouched.col(), TileColor.OFF);

        // برو تایل بعدی
        int nextIndex = currentIndex + 1;
        ctx.state().put(KEY_INDEX, nextIndex);

        if (nextIndex >= positions.size()) {
            // همه تایل‌ها تمام شد → برد
            handleWin(ctx);
        } else {
            // تایل بعدی را روشن کن
            lightCurrentTile(ctx);
        }
    }

    private void handleWrongTouch(GameContext ctx) {
        log.info("[{}] Wrong tile touched!", ctx.sessionId());

        // انیمیشن lose کوتاه: FADE_TO_RED (حدود 1.5 ثانیه)
        // چون AnimationSystem فقط یک انیمیشن همزمان دارد، این انیمیشن تایل فعلی را موقتا override می‌کند
        // بعد از اتمام، دوباره تایل جاری را روشن می‌کنیم
        ctx.animations().playLoseAnimation(AnimationSystem.LoseAnimationType.FADE_TO_RED)
                .thenRun(() -> {
                    // این thenRun روی animation thread اجرا می‌شود، اما setTile thread-safe است (BoardChannel)
                    lightCurrentTile(ctx);
                });
    }

    private void handleWin(GameContext ctx) {
        log.info("[{}] All tiles touched! Player WINS", ctx.sessionId());

        // تایمر را متوقف کن
        ctx.timer().stop();

        // انیمیشن win: RADIAL_BURST
        // سپس winSession که باعث finishSession در GameSessionImpl می‌شود
        // finishSession با CAS (compareAndSet) تضمین می‌کند فقط یک بار اجرا شود (SessionLifecycle)
        ctx.animations().playWinAnimation(AnimationSystem.WinAnimationType.RADIAL_BURST)
                .thenRun(() -> {
                    // winSession لیست برندگان را می‌گیرد
                    ctx.winSession(ctx.players());
                });
    }

    private void lightCurrentTile(GameContext ctx) {
        @SuppressWarnings("unchecked")
        List<Position> positions = ctx.state().get(KEY_POSITIONS, List.class).orElse(List.of());
        int index = ctx.state().getOrDefault(KEY_INDEX, Integer.class, 0);

        if (index < 0 || index >= positions.size()) return;

        Position pos = positions.get(index);
        // رنگ بر اساس ایندکس از پالت انتخاب می‌شود تا تنوع داشته باشد
        TileColor color = PALETTE[index % PALETTE.length];

        // BoardChannel.setTile -> stateLock (ReentrantLock) برای کپی بافر + gatewayWriteLock (synchronized) برای ارسال
        // این تضمین می‌کند حتی اگر onTileEvent و onTick همزمان setTile کنند، فریم‌ها روی سیم interleave نشوند
        ctx.setTile(pos.row(), pos.col(), color);

        log.debug("[{}] Lit tile {} at {} with {}", ctx.sessionId(), index, pos, color);
    }

    @Override
    public void onStop(GameContext ctx, GameResult result) {
        log.info("[{}] SequentialTouchGame onStop - status={}, scores={}", ctx.sessionId(), result.finalStatus(), result.finalScores());

        // برد را خاموش کن (best-effort)
        try {
            ctx.fillBoard(TileColor.OFF);
        } catch (Exception e) {
            log.warn("[{}] Could not clear board on stop (gateway may be disconnected)", ctx.sessionId());
        }

        // انیمیشن‌های پایانی را cancel کن و منابع را آزاد کن
        ctx.animations().cancelCurrent();
    }

    @Override
    public void onError(GameContext ctx, Throwable error) {
        log.error("[{}] Game error", ctx.sessionId(), error);
        // در صورت خطا، انیمیشن lose و سپس توقف جلسه
        try {
            ctx.animations().playLoseAnimation(AnimationSystem.LoseAnimationType.PULSE_RED)
                    .get(2, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("[{}] Lose animation interrupted on error", ctx.sessionId());
        } finally {
            ctx.stopSession();
        }
    }
}
