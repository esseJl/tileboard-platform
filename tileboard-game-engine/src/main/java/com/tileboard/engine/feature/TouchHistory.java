package com.tileboard.engine.feature;

import com.tileboard.engine.model.TileEvent;
import com.tileboard.engine.model.TouchSequence;
import com.tileboard.serial.board.Position;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public final class TouchHistory {
    private static final Logger log = LoggerFactory.getLogger(TouchHistory.class);

    private final String sessionId;
    private final int maxSize;
    private final Deque<TileEvent> history = new ArrayDeque<>();
    private final Object lock = new Object();
    private final Map<Position, TileEvent> active = new ConcurrentHashMap<>();
    private long totalTouches = 0;

    public TouchHistory(String sessionId) {
        this(sessionId, 2_000);
    }

    public TouchHistory(String sessionId, int maxSize) {
        if (maxSize <= 0) throw new IllegalArgumentException("maxSize must be > 0");
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.maxSize = maxSize;
    }

    public String sessionId() {
        return sessionId;
    }

    public void record(TileEvent event) {
        synchronized (lock) {
            if (history.size() >= maxSize) history.pollFirst();
            history.addLast(event);
            totalTouches++;
            updateActive(event);
        }
    }

    /**
     * Must only be called while holding {@link #lock}.
     */
    private void updateActive(TileEvent event) {
        this.active.clear();
        switch (event.type()) {
            case TOUCH, HOLD -> active.put(event.position(), event);
        }
    }

    public int totalTouches() {
        synchronized (lock) {
            return (int) Math.min(totalTouches, Integer.MAX_VALUE);
        }
    }

    public Optional<TileEvent> last() {
        synchronized (lock) {
            return Optional.ofNullable(history.peekLast());
        }
    }

    public Optional<Position> lastTouchedPosition() {
        return last().map(TileEvent::position);
    }

    public List<TileEvent> activeTouches() {
        return List.copyOf(active.values());
    }

    /**
     * The most recent {@code limit} touch events, newest first (i.e. element
     * {@code 0} is always the very last touch recorded — the "live" one).
     * Returns fewer than {@code limit} elements while the history hasn't
     * accumulated that many yet, and an empty list once {@link #reset()} has
     * been called or before anything has been recorded.
     *
     * @throws IllegalArgumentException if {@code limit} is negative
     */
    public List<TileEvent> recent(int limit) {
        if (limit < 0) throw new IllegalArgumentException("limit must be >= 0");
        if (limit == 0) return List.of();
        synchronized (lock) {
            int n = Math.min(limit, history.size());
            List<TileEvent> result = new ArrayList<>(n);
            Iterator<TileEvent> it = history.descendingIterator();
            while (result.size() < n && it.hasNext()) {
                result.add(it.next());
            }
            return List.copyOf(result);
        }
    }

    public TouchSequence sequence() {
        synchronized (lock) {
            List<Position> pos = new ArrayList<>(history.size());
            List<Instant> ts = new ArrayList<>(history.size());
            for (TileEvent e : history) {
                pos.add(e.position());
                ts.add(e.occurredAt());
            }
            return new TouchSequence(pos, ts);
        }
    }

    public List<Position> positionOrder() {
        synchronized (lock) {
            return history.stream().map(TileEvent::position).toList();
        }
    }

    public Set<Position> distinctPositions() {
        synchronized (lock) {
            return history.stream().map(TileEvent::position)
                    .collect(Collectors.toUnmodifiableSet());
        }
    }

    public void reset() {
        synchronized (lock) {
            history.clear();
            totalTouches = 0;
        }
        log.debug("TouchHistory reset for session {}", sessionId);
    }
}