package com.tileboard.serial.gateway;

import com.tileboard.serial.board.Board;

/**
 * Notified with an already-decoded {@link Board} whenever a frame matching
 * the {@code Command} a {@link BoardFrameListener} was registered for
 * arrives. This is the board-shaped counterpart to {@link FrameListener}:
 * use it whenever a command's payload is a flat, row-major array of tiles
 * (typically {@code DATA_IN}/{@code DATA_OUT}) and the application wants to
 * work with {@link Board} coordinates instead of raw bytes.
 *
 * @param <T> the application's tile type, e.g. {@code Boolean} for touch
 *            state or a color enum
 */
@FunctionalInterface
public interface BoardListener<T> {

    void onBoard(Board<T> board);
}
