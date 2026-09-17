package com.tileboard.engine.feature;

import com.tileboard.engine.model.TileEvent;
import com.tileboard.engine.model.TouchSequence;
import com.tileboard.serial.board.Position;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Records every {@link TileEvent} for a session and exposes query methods:
 * last touched, count, ordered sequence, inter-touch timing.
 */
public final class TouchHistory {

    private final String sessionId;
    private final List<TileEvent> history = new CopyOnWriteArrayList<>();

    public TouchHistory(String sessionId) {
        this.sessionId = sessionId;
    }

    public void record(TileEvent event) {
        history.add(event);
    }

    public int totalTouches() { return history.size(); }

    public Optional<TileEvent> last() {
        if (history.isEmpty()) return Optional.empty();
        return Optional.of(history.get(history.size() - 1));
    }

    public Optional<Position> lastTouchedPosition() {
        return last().map(TileEvent::position);
    }

    /** Snapshot of the current touch sequence (positions + timestamps). */
    public TouchSequence sequence() {
        List<Position> pos = new ArrayList<>();
        List<Instant>  ts  = new ArrayList<>();
        history.forEach(e -> { pos.add(e.position()); ts.add(e.occurredAt()); });
        return new TouchSequence(pos, ts);
    }

    /** All positions touched, in order, since the session started or last {@link #reset()}. */
    public List<Position> positionOrder() {
        return history.stream().map(TileEvent::position).toList();
    }

    /** The set of distinct positions that have been touched at least once. */
    public Set<Position> distinctPositions() {
        return history.stream().map(TileEvent::position)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public void reset() { history.clear(); }
}