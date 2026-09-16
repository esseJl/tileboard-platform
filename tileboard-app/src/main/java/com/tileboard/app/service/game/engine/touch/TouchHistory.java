package com.tileboard.app.service.game.engine.touch;

import com.tileboard.serial.board.Position;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Built-in tracker of all touches during a game session.
 *
 * <p>Provides:
 * <ul>
 *   <li>the last touched house / position</li>
 *   <li>total number of touches</li>
 *   <li>ordered sequence of touches</li>
 *   <li>time deltas between consecutive touches</li>
 * </ul>
 */
public final class TouchHistory {

    private final List<TouchEvent> events = new ArrayList<>();
    private final AtomicLong sequence = new AtomicLong(0);

    public synchronized void record(Position position) {
        events.add(new TouchEvent(position, Instant.now(), sequence.getAndIncrement()));
    }

    public synchronized void record(Position position, Instant at) {
        events.add(new TouchEvent(position, at, sequence.getAndIncrement()));
    }

    public synchronized Optional<TouchEvent> last() {
        return events.isEmpty() ? Optional.empty() : Optional.of(events.get(events.size() - 1));
    }

    public synchronized Optional<Position> lastPosition() {
        return last().map(TouchEvent::position);
    }

    public synchronized int size() {
        return events.size();
    }

    public synchronized List<TouchEvent> asList() {
        return Collections.unmodifiableList(new ArrayList<>(events));
    }

    public synchronized List<Position> positionsInOrder() {
        return events.stream().map(TouchEvent::position).toList();
    }

    /**
     * Time elapsed between the two most recent touches, or empty if fewer than two.
     */
    public synchronized Optional<Duration> lastInterval() {
        if (events.size() < 2) {
            return Optional.empty();
        }
        TouchEvent a = events.get(events.size() - 2);
        TouchEvent b = events.get(events.size() - 1);
        return Optional.of(Duration.between(a.timestamp(), b.timestamp()));
    }

    /**
     * Average interval between consecutive touches (empty if &lt; 2 events).
     */
    public synchronized Optional<Duration> averageInterval() {
        if (events.size() < 2) {
            return Optional.empty();
        }
        long totalNanos = 0;
        for (int i = 1; i < events.size(); i++) {
            totalNanos += Duration.between(events.get(i - 1).timestamp(), events.get(i).timestamp()).toNanos();
        }
        return Optional.of(Duration.ofNanos(totalNanos / (events.size() - 1)));
    }

    public synchronized void clear() {
        events.clear();
        sequence.set(0);
    }
}
