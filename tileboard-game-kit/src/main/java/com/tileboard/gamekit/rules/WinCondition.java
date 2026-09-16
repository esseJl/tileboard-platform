package com.tileboard.gamekit.rules;

@FunctionalInterface
public interface WinCondition<S> {
    boolean isWon(S state);
}
