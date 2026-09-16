package com.tileboard.gamekit.rules;

@FunctionalInterface
public interface ScorePolicy {
    long points(long basePoints, int combo, long reactionMillis);
}
