package com.tileboard.engine.core;

import com.tileboard.engine.codec.ColorTileCodec;
import com.tileboard.engine.model.TileColor;
import com.tileboard.serial.board.Board;
import com.tileboard.serial.gateway.TileGatewayClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

@ExtendWith(MockitoExtension.class)
class BoardChannelConcurrencyTest {

    @Mock
    TileGatewayClient gateway;

    @Test
    void aSlowGatewayWriteDoesNotBlockStateReads() throws Exception {
        CountDownLatch firstWriteEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstWrite = new CountDownLatch(1);

        doAnswer(inv -> {
            firstWriteEntered.countDown();
            releaseFirstWrite.await(5, TimeUnit.SECONDS);
            return null;
        }).when(gateway).sendBoard(any(), any(), any(), any());

        BoardChannel channel = new BoardChannel(4, 4, gateway, ColorTileCodec.instance());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> firstWrite = pool.submit(() -> channel.fill(TileColor.RED));
            assertTrue(firstWriteEntered.await(2, TimeUnit.SECONDS),
                    "first write never reached the gateway");

            Future<Board<TileColor>> read = pool.submit(channel::snapshot);
            Board<TileColor> snap = null;
            try {
                snap = read.get(500, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                fail("snapshot() blocked on a slow concurrent gateway write");
            } finally {
                releaseFirstWrite.countDown();
            }

            assertEquals(TileColor.RED, snap.get(0, 0));
            firstWrite.get(2, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }
}