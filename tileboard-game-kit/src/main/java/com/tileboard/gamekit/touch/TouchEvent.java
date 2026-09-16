package com.tileboard.gamekit.touch;

import com.tileboard.serial.board.Position;

import java.time.Duration;
import java.time.Instant;

/**
 * One recorded touch, as produced by {@link TouchTracker}: which cell
 * ("خانه‌ی لمس‌شده"), its place in the overall touch order ("ترتیب لمس"),
 * when it happened, and how long it had been since the previous touch
 * ("زمان بین لمس‌ها" - {@link Duration#ZERO} for the very first touch).
 */
public record TouchEvent(Position position, long sequenceNumber, Instant touchedAt, Duration sinceLastTouch) {
}
