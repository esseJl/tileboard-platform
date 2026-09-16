package com.tileboard.gamekit.time;

import java.util.List;
import java.util.Objects;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

/** Injectable random source; deterministic implementations can be supplied in tests. */
@FunctionalInterface
public interface RandomSource {
    int nextInt(int bound);

    default boolean nextBoolean() { return nextInt(2) == 1; }

    default <T> T pick(List<T> values) {
        Objects.requireNonNull(values, "values");
        if (values.isEmpty()) throw new IllegalArgumentException("values must not be empty");
        return values.get(nextInt(values.size()));
    }

    static RandomSource threadLocal() {
        return bound -> java.util.concurrent.ThreadLocalRandom.current().nextInt(bound);
    }

    static RandomSource seeded(long seed) {
        RandomGenerator generator = RandomGeneratorFactory.<RandomGenerator>of("L64X128MixRandom").create(seed);
        return generator::nextInt;
    }
}
