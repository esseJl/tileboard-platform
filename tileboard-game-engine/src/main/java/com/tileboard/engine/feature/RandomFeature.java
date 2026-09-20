package com.tileboard.engine.feature;

import com.tileboard.engine.model.TileColor;
import com.tileboard.serial.board.Position;

import java.util.*;

/**
 * Seeded, reproducible random helpers for board games.
 */
public final class RandomFeature {

    private final int width;
    private final int height;
    private final Random rng;

    public RandomFeature(int width, int height) {
        this(width, height, new Random());
    }

    public RandomFeature(int width, int height, long seed) {
        this(width, height, new Random(seed));
    }

    private RandomFeature(int width, int height, Random rng) {
        this.width = width;
        this.height = height;
        this.rng = rng;
    }

    public Position randomPosition() {
        return new Position(rng.nextInt(height), rng.nextInt(width));
    }

    public List<Position> randomPositions(int count) {
        List<Position> all = new ArrayList<>();
        for (int r = 0; r < height; r++)
            for (int c = 0; c < width; c++) all.add(new Position(r, c));
        Collections.shuffle(all, rng);
        return all.subList(0, Math.min(count, all.size()));
    }

    public TileColor randomColor(TileColor... exclude) {
        List<TileColor> pool = new ArrayList<>(Arrays.asList(TileColor.values()));
        pool.remove(TileColor.OFF);
        pool.removeAll(Arrays.asList(exclude));
        if (pool.isEmpty()) {
            throw new IllegalArgumentException(
                    "randomColor(): no candidate colors left after excluding " + Arrays.toString(exclude)
                            + " — cannot pick a random color");
        }
        return pool.get(rng.nextInt(pool.size()));
    }

    public <T> T pick(List<T> list) {
        if (list.isEmpty()) throw new NoSuchElementException("list is empty");
        return list.get(rng.nextInt(list.size()));
    }

    public boolean chance(double probability) {
        return rng.nextDouble() < probability;
    }

    public void reseed(long seed) {
        rng.setSeed(seed);
    }
}