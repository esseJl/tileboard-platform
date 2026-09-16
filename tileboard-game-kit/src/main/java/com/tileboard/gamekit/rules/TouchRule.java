package com.tileboard.gamekit.rules;

import com.tileboard.gamekit.model.TileObservation;

@FunctionalInterface
public interface TouchRule {
    boolean test(TileObservation touch);
}
