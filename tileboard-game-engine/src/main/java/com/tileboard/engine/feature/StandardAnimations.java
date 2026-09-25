package com.tileboard.engine.feature;

import com.tileboard.engine.feature.anim.countdown.ScalableCountdownAnimation;
import com.tileboard.engine.feature.anim.countdown.SimpleCountdownAnimation;
import com.tileboard.engine.feature.anim.lose.*;
import com.tileboard.engine.feature.anim.standby.*;
import com.tileboard.engine.feature.anim.win.*;

public final class StandardAnimations {
    public static final String COUNTDOWN_SIMPLE = "countdown.simple";
    public static final String COUNTDOWN_SCALABLE = "countdown.scalable";

    public static final String WIN_RADIAL_BURST = "win.radial-burst";
    public static final String WIN_RAINBOW_SWEEP = "win.rainbow-sweep";
    public static final String WIN_SPARKLE = "win.sparkle";
    public static final String WIN_FIREWORKS = "win.fireworks";

    public static final String LOSE_FADE_TO_RED = "lose.fade-to-red";
    public static final String LOSE_DESCENDING_CURTAIN = "lose.descending-curtain";
    public static final String LOSE_CRUMBLE = "lose.crumble";
    public static final String LOSE_PULSE_RED = "lose.pulse-red";

    public static final String STANDBY_BREATHING = "standby.breathing";
    public static final String STANDBY_CORNER_PULSE = "standby.corner-pulse";
    public static final String STANDBY_WAVE_BORDER = "standby.wave-border";
    public static final String STANDBY_RANDOM_TWINKLE = "standby.random-twinkle";

    private StandardAnimations() {
    }

    public static void registerAll(AnimationRegistry registry) {
        registry.register(COUNTDOWN_SIMPLE, new SimpleCountdownAnimation());
        registry.register(COUNTDOWN_SCALABLE, new ScalableCountdownAnimation());

        registry.register(WIN_RADIAL_BURST, new RadialBurstAnimation());
        registry.register(WIN_RAINBOW_SWEEP, new RainbowSweepAnimation());
        registry.register(WIN_SPARKLE, new SparkleAnimation());
        registry.register(WIN_FIREWORKS, new FireworksAnimation());

        registry.register(LOSE_FADE_TO_RED, new FadeToRedAnimation());
        registry.register(LOSE_DESCENDING_CURTAIN, new DescendingCurtainAnimation());
        registry.register(LOSE_CRUMBLE, new CrumbleAnimation());
        registry.register(LOSE_PULSE_RED, new PulseRedAnimation());

        registry.register(STANDBY_BREATHING, new BreathingAnimation());
        registry.register(STANDBY_CORNER_PULSE, new CornerPulseAnimation());
        registry.register(STANDBY_WAVE_BORDER, new WaveBorderAnimation());
        registry.register(STANDBY_RANDOM_TWINKLE, new RandomTwinkleAnimation());
    }

    public static String forWin(AnimationSystem.WinAnimationType type) {
        return switch (type) {
            case RADIAL_BURST -> WIN_RADIAL_BURST;
            case RAINBOW_SWEEP -> WIN_RAINBOW_SWEEP;
            case SPARKLE -> WIN_SPARKLE;
            case FIREWORKS -> WIN_FIREWORKS;
        };
    }

    public static String forLose(AnimationSystem.LoseAnimationType type) {
        return switch (type) {
            case FADE_TO_RED -> LOSE_FADE_TO_RED;
            case DESCENDING_CURTAIN -> LOSE_DESCENDING_CURTAIN;
            case CRUMBLE -> LOSE_CRUMBLE;
            case PULSE_RED -> LOSE_PULSE_RED;
        };
    }

    public static String forStandby(AnimationSystem.StandbyAnimationType type) {
        return switch (type) {
            case BREATHING -> STANDBY_BREATHING;
            case CORNER_PULSE -> STANDBY_CORNER_PULSE;
            case WAVE_BORDER -> STANDBY_WAVE_BORDER;
            case RANDOM_TWINKLE -> STANDBY_RANDOM_TWINKLE;
        };
    }
}