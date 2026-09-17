package com.tileboard.engine.feature.neighbor;

/**
 * Defines which positions are considered "adjacent" for the
 * {@link NeighborFinder}.
 */
public enum Adjacency {
    /** North, South, East, West (4 cells). */
    FOUR_WAY,
    /** All 8 surrounding cells including diagonals. */
    EIGHT_WAY,
    /** Cardinal + diagonals but only diagonal directions. */
    DIAGONAL_ONLY
}