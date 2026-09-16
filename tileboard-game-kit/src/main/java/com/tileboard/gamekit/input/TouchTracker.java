package com.tileboard.gamekit.input;

import com.tileboard.gamekit.model.TileObservation;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Facade for touch-derived state. Game code can use this instead of maintaining
 * its own count/order/timing bookkeeping.
 */
public final class TouchTracker {
    private final TouchSequence sequence = new TouchSequence();
    private final Function<TouchEvent, String> tileColorResolver;
    private final Function<TouchEvent, String> targetColorResolver;

    public TouchTracker(Function<TouchEvent, String> tileColorResolver,
                         Function<TouchEvent, String> targetColorResolver) {
        this.tileColorResolver = Objects.requireNonNull(tileColorResolver, "tileColorResolver");
        this.targetColorResolver = Objects.requireNonNull(targetColorResolver, "targetColorResolver");
    }

    public TileObservation record(TouchEvent event) {
        return sequence.add(event, tileColorResolver.apply(event), targetColorResolver.apply(event));
    }

    public int count() { return sequence.size(); }
    public List<TileObservation> observations() { return sequence.snapshot(); }
    public List<com.tileboard.gamekit.model.TilePosition> path() { return sequence.positions(); }
    public void reset() { sequence.clear(); }
}
