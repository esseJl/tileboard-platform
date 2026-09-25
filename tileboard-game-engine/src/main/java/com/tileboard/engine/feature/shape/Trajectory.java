package com.tileboard.engine.feature.shape;

import com.tileboard.serial.board.Position;

@FunctionalInterface
public interface Trajectory {
    Position at(double t); // t in [0,1]

    default double lengthCells(int samples) {
        double length = 0;
        Position prev = at(0);
        for (int i = 1; i <= samples; i++) {
            Position curr = at((double) i / samples);
            length += Math.hypot(curr.row() - prev.row(), curr.col() - prev.col());
            prev = curr;
        }
        return length;
    }
}