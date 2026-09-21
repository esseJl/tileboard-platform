package com.tileboard.engine.core;

import com.tileboard.engine.model.TileColor;
import com.tileboard.serial.board.Board;
import com.tileboard.serial.board.TileCodec;
import com.tileboard.serial.gateway.TileGatewayClient;
import com.tileboard.serial.protocol.Command;
import com.tileboard.serial.protocol.CommandType;

import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Publishes the latest committed board state to the gateway.
 *
 * <h2>Coalescing semantics</h2>
 * This method intentionally re-reads {@link #snapshot()} <em>after</em> acquiring
 * {@code gatewayWriteLock}, rather than sending the exact state captured by the
 * caller. Under concurrent writers, this means a slightly stale caller may end up
 * transmitting a newer state than the one it produced — which is by design: the
 * gateway only ever needs the most recent board, and this avoids sending
 * superseded frames out of order. Callers must not assume the {@link Board}
 * returned by {@link #setTile}/{@link #fill}/{@link #publish} is byte-for-byte
 * identical to what was last transmitted.
 */
public final class BoardChannel {

    private final int width;
    private final int height;
    private final TileGatewayClient gateway;
    private final TileCodec<TileColor> codec;

    private final ReentrantLock stateLock = new ReentrantLock();
    private final Object gatewayWriteLock = new Object();
    private final Board<TileColor> buffer;
    private Board<TileColor> lastSentBoard;

    public BoardChannel(int width, int height, TileGatewayClient gateway, TileCodec<TileColor> codec) {
        this.width = width;
        this.height = height;
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.buffer = new Board<>(width, height, TileColor.OFF);
    }

    /**
     * Replaces the whole board with {@code board} and pushes it to hardware.
     */
    public Board<TileColor> publish(Board<TileColor> board) {
        Board<TileColor> snapshot = board.copy();
        stateLock.lock();
        try {
            snapshot.forEach(buffer::set);
        } finally {
            stateLock.unlock();
        }
        sendLatest();
        return snapshot;
    }

    public Board<TileColor> setTile(int row, int col, TileColor color) {
        Board<TileColor> snapshot;
        stateLock.lock();
        try {
            buffer.set(row, col, color);
            snapshot = buffer.copy();
        } finally {
            stateLock.unlock();
        }
        sendLatest();
        return snapshot;
    }

    public Board<TileColor> fill(TileColor color) {
        Board<TileColor> snapshot;
        stateLock.lock();
        try {
            buffer.fill(color);
            snapshot = buffer.copy();
        } finally {
            stateLock.unlock();
        }
        sendLatest();
        return snapshot;
    }

    /**
     * Best-effort clear; swallows gateway errors since this is typically called during teardown.
     */
    public void tryClear() {
        try {
            fill(TileColor.OFF);
        } catch (RuntimeException e) {
            // Intentionally swallowed: the gateway may already be disconnected during session teardown.
        }
    }

    public Board<TileColor> newEmptyBoard() {
        return new Board<>(width, height, TileColor.OFF);
    }

    /**
     * Returns a copy of the current logical board state without touching hardware.
     */
    public Board<TileColor> snapshot() {
        stateLock.lock();
        try {
            return buffer.copy();
        } finally {
            stateLock.unlock();
        }
    }

    private Board<TileColor> sendLatest() {
        synchronized (gatewayWriteLock) {
            Board<TileColor> latest = snapshot();
            if (latest.equals(lastSentBoard)) return latest;
            gateway.sendBoard(Command.DATA_OUT, CommandType.SET, latest, codec);
            lastSentBoard = latest;
            return latest;
        }
    }
}