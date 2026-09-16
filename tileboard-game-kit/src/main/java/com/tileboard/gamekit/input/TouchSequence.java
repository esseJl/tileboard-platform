package com.tileboard.gamekit.input;

import com.tileboard.gamekit.model.TileObservation;
import com.tileboard.gamekit.model.TilePosition;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Thread-safe touch history with O(1) append and immutable snapshots. */
public final class TouchSequence {
    private final Object lock = new Object();
    private final List<TileObservation> observations = new ArrayList<>();

    public TileObservation add(TouchEvent event, String tileColor, String targetColor) {
        Objects.requireNonNull(event, "event");
        synchronized (lock) {
            Duration delta = observations.isEmpty()
                    ? Duration.ZERO
                    : Duration.between(observations.get(observations.size() - 1).timestamp(), event.timestamp());
            if (delta.isNegative()) delta = Duration.ZERO;
            TileObservation observation = new TileObservation(
                    event.position(), observations.size() + 1L, event.timestamp(), delta, tileColor, targetColor);
            observations.add(observation);
            return observation;
        }
    }

    public int size() {
        synchronized (lock) { return observations.size(); }
    }

    public boolean contains(TilePosition position) {
        synchronized (lock) { return observations.stream().anyMatch(o -> o.position().equals(position)); }
    }

    public List<TileObservation> snapshot() {
        synchronized (lock) { return List.copyOf(observations); }
    }

    public List<TilePosition> positions() {
        synchronized (lock) { return observations.stream().map(TileObservation::position).toList(); }
    }

    public void clear() {
        synchronized (lock) { observations.clear(); }
    }
}
