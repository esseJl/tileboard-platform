package com.tileboard.gamekit.touch;

import com.tileboard.serial.board.Position;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * The built-in touch bookkeeping capability, covering everything in the
 * "خانه‌ی لمس‌شده / تعداد خانه‌های لمس‌شده / ترتیب لمس / زمان بین لمس‌ها"
 * family: which cell was touched, how many times each cell has been
 * touched, the order touches happened in, and the gap since the previous
 * touch. A game's {@code onPlayerInput(Board<Boolean>)} feeds every touched
 * position from the incoming board into {@link #record}; everything else
 * is derived automatically.
 *
 * <p>Thread-safe: {@link #record} is called from whatever thread reports
 * player input (a serial gateway callback thread in the app), while a game
 * may read totals or history from its own clock-tick thread. All mutation
 * happens inside a single {@code synchronized} block; the touch history is
 * additionally exposed via a {@link CopyOnWriteArrayList} snapshot so
 * {@link #history()} never has to copy on every read.
 */
public final class TouchTracker {

    private final Object lock = new Object();
    private final Map<Position, Long> countsByPosition = new HashMap<>();
    private final List<TouchEvent> history = new CopyOnWriteArrayList<>();
    private final List<Consumer<TouchEvent>> listeners = new CopyOnWriteArrayList<>();

    private long sequence = 0;
    private Instant lastTouchAt;

    /** Registers a listener invoked (synchronously, on the calling thread) every time {@link #record} is called. */
    public void onTouch(Consumer<TouchEvent> listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /** Records a single touch at {@code position} "now" and returns the resulting {@link TouchEvent}. */
    public TouchEvent record(Position position) {
        Objects.requireNonNull(position, "position");
        TouchEvent event;
        synchronized (lock) {
            Instant now = Instant.now();
            Duration sinceLast = lastTouchAt == null ? Duration.ZERO : Duration.between(lastTouchAt, now);
            event = new TouchEvent(position, ++sequence, now, sinceLast);
            lastTouchAt = now;
            countsByPosition.merge(position, 1L, Long::sum);
            history.add(event);
        }
        listeners.forEach(listener -> listener.accept(event));
        return event;
    }

    /** {@link #record} for every position in {@code positions}, in iteration order. Convenient for a whole {@code Board.positionsWhere(...)} batch. */
    public List<TouchEvent> recordAll(List<Position> positions) {
        List<TouchEvent> events = new ArrayList<>(positions.size());
        for (Position position : positions) {
            events.add(record(position));
        }
        return List.copyOf(events);
    }

    /** Total number of touches recorded across every cell ("تعداد خانه‌های لمس‌شده" summed). */
    public long totalTouches() {
        synchronized (lock) {
            return sequence;
        }
    }

    /** How many times {@code position} specifically has been touched. */
    public long touchCountAt(Position position) {
        synchronized (lock) {
            return countsByPosition.getOrDefault(position, 0L);
        }
    }

    /** The most recent touch, if any. */
    public Optional<TouchEvent> lastTouch() {
        List<TouchEvent> snapshot = history;
        return snapshot.isEmpty() ? Optional.empty() : Optional.of(snapshot.get(snapshot.size() - 1));
    }

    /** The full touch history in order ("ترتیب لمس"), oldest first. A live, read-only view - safe to iterate while touches keep arriving. */
    public List<TouchEvent> history() {
        return List.copyOf(history);
    }

    /** Resets all counts and history, e.g. between rounds of the same session. */
    public void reset() {
        synchronized (lock) {
            countsByPosition.clear();
            history.clear();
            sequence = 0;
            lastTouchAt = null;
        }
    }
}
