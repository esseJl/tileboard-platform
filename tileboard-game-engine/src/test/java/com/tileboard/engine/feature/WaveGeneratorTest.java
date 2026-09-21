package com.tileboard.engine.feature;


import com.tileboard.engine.model.TileColor;
import com.tileboard.serial.board.Board;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

class WaveGeneratorTest {

    @Test
    void startingANewEffectCancelsThePreviousOneAndDoesNotBlockTheCaller() throws Exception {
        CopyOnWriteArrayList<Board<TileColor>> published = new CopyOnWriteArrayList<>();
        WaveGenerator waves = new WaveGenerator(5, 1, published::add);
        try {
            long start = System.nanoTime();
            waves.sweepDown(TileColor.RED, 5_000); // اگر لغو نشود ۲۵ ثانیه طول می‌کشد
            Thread.sleep(50);
            CompletableFuture<Void> second = waves.blink(TileColor.GREEN, TileColor.OFF, 1, 10);

            second.get(2, TimeUnit.SECONDS);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            assertTrue(elapsedMs < 2000, "افکت دوم نباید منتظر تمام‌شدن اولی بماند");
        } finally {
            waves.close();
        }
    }
}
