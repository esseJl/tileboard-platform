package com.tileboard.engine.feature.shape;

import com.tileboard.serial.board.Position;

public final class Trajectories {
    private Trajectories() {
    }

    public static Trajectory linear(Position from, Position to) {
        return t -> new Position(
                (int) Math.round(from.row() + (to.row() - from.row()) * t),
                (int) Math.round(from.col() + (to.col() - from.col()) * t));
    }

    /**
     * Parabolic jump — peaks at t=0.5, {@code heightCells} above the straight line.
     */
    public static Trajectory jumpArc(Position from, Position to, double heightCells) {
        return t -> {
            double lift = heightCells * 4 * t * (1 - t);
            int row = (int) Math.round(from.row() + (to.row() - from.row()) * t - lift);
            int col = (int) Math.round(from.col() + (to.col() - from.col()) * t);
            return new Position(row, col);
        };
    }

    /**
     * Several decaying jump arcs — a ball settling down.
     */
    public static Trajectory bounce(Position from, Position to, int bounces, double heightCells) {
        return t -> {
            double segment = 1.0 / bounces;
            int index = Math.min(bounces - 1, (int) (t / segment));
            double localT = (t - index * segment) / segment;
            double decay = Math.pow(0.6, index);
            double lift = heightCells * decay * 4 * localT * (1 - localT);
            int row = (int) Math.round(from.row() + (to.row() - from.row()) * t - lift);
            int col = (int) Math.round(from.col() + (to.col() - from.col()) * t);
            return new Position(row, col);
        };
    }

    public static Trajectory orbit(Position center, double radius, double startAngleRad, double turns) {
        return t -> {
            double angle = startAngleRad + turns * 2 * Math.PI * t;
            int row = (int) Math.round(center.row() + radius * Math.sin(angle));
            int col = (int) Math.round(center.col() + radius * Math.cos(angle));
            return new Position(row, col);
        };
    }

    public static Trajectory zigzag(Position from, Position to, int zigzags, double amplitude) {
        return t -> {
            double baseRow = from.row() + (to.row() - from.row()) * t;
            double baseCol = from.col() + (to.col() - from.col()) * t;
            double perpendicular = amplitude * Math.sin(t * zigzags * Math.PI);
            return new Position((int) Math.round(baseRow + perpendicular), (int) Math.round(baseCol));
        };
    }
}