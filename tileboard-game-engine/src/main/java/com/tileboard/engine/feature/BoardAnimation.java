package com.tileboard.engine.feature;

@FunctionalInterface
public interface BoardAnimation {
    /**
     * Runs until {@code token.sleep(...)}/{@code token.pause(...)} signals cancellation
     * or the effect completes naturally.
     */
    void run(AnimationSystem.RunToken token, AnimationContext ctx);
}