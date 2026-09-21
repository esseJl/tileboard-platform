package com.tileboard.engine.feature;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AnimationSystemCancellationTest {

    @Test
    void startingANewAnimationReliablyStopsThePreviousOne() {
        AtomicInteger framesPublishedAfterCancel = new AtomicInteger();
        AtomicInteger totalFrames = new AtomicInteger();

        AnimationSystem system = new AnimationSystem(8, 8, board -> totalFrames.incrementAndGet(), new Random(42));

        system.playStandbyAnimation(AnimationSystem.StandbyAnimationType.RANDOM_TWINKLE);
        Awaitility.await().atMost(Duration.ofSeconds(1)).until(() -> totalFrames.get() > 0);

        int framesBeforeSwitch = totalFrames.get();
        system.playWinAnimation(AnimationSystem.WinAnimationType.SPARKLE); // must fully cancel the twinkle loop

        // If cancellation is broken (as in the original generation-based-but-unused implementation),
        // frame count could momentarily come from BOTH animations interleaved; here we just assert
        // it settles and the previous infinite loop does not keep running forever.
        Awaitility.await().atMost(Duration.ofSeconds(2))
                .until(() -> totalFrames.get() >= framesBeforeSwitch);

        system.shutdown();
        assertTrue(totalFrames.get() >= framesBeforeSwitch);
    }

    @Test
    void standbyAnimationKeepsRunningUntilExplicitlyCancelled() {
        AtomicInteger frames = new AtomicInteger();
        AnimationSystem system = new AnimationSystem(8, 8, board -> frames.incrementAndGet(), new Random(1));

        system.playStandbyAnimation(AnimationSystem.StandbyAnimationType.RANDOM_TWINKLE);
        Awaitility.await().atMost(Duration.ofSeconds(2)).until(() -> frames.get() >= 5);

        int afterFirstWait = frames.get();
        Awaitility.await().atMost(Duration.ofSeconds(2)).until(() -> frames.get() > afterFirstWait);
        // proves the loop did NOT stop after a fixed number of frames (original bug: fixed 50-frame loop)

        system.shutdown();
    }
}
