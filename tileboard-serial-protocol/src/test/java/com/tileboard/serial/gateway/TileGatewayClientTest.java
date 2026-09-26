package com.tileboard.serial.gateway;

import com.tileboard.serial.board.Board;
import com.tileboard.serial.board.Position;
import com.tileboard.serial.board.TileCodec;
import com.tileboard.serial.protocol.Command;
import com.tileboard.serial.protocol.CommandType;
import com.tileboard.serial.protocol.DefaultFrameCodec;
import com.tileboard.serial.protocol.Frame;
import com.tileboard.serial.transport.DataListener;
import com.tileboard.serial.transport.SerialTransport;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TileGatewayClientTest {

    @Test
    void addBoardListenerDecodesADataInFrameArrivingOnTheTransport() throws InterruptedException {
        FakeTransport transport = new FakeTransport();
        // Run listener callbacks synchronously so the test doesn't need to poll/sleep.
        Executor synchronousExecutor = Runnable::run;

        TileGatewayClient client = TileGatewayClient.builder()
                .transport(transport)
                .callbackExecutor(synchronousExecutor)
                .build();
        client.start();

        BlockingQueue<Board<Boolean>> received = new ArrayBlockingQueue<>(1);
        client.addBoardListener(Command.DATA_IN, 2, 2, TileCodec.booleanState(), received::add);

        byte[] wireFrame = new DefaultFrameCodec().encode(Frame.of(Command.DATA_IN, CommandType.SET, new byte[]{0, 1, 0, 1}));
        transport.deliver(wireFrame);

        Board<Boolean> touchBoard = received.poll(1, TimeUnit.SECONDS);
        assertTrue(touchBoard != null);
        assertEquals(List.of(new Position(0, 1), new Position(1, 1)), touchBoard.positionsWhere(Boolean.TRUE::equals));

        client.close();
    }

    /**
     * Minimal in-memory {@link SerialTransport} - no real port is opened for this test.
     */
    private static final class FakeTransport implements SerialTransport {
        private DataListener listener;

        void deliver(byte[] bytes) {
            if (listener != null) {
                listener.onDataReceived(bytes);
            }
        }

        @Override
        public String portName() {
            return "FAKE";
        }

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public boolean isPhysicallyConnected() {
            return true;
        }

        @Override
        public void write(byte[] data) {
            // not exercised by this test
        }

        @Override
        public void setDataListener(DataListener listener) {
            this.listener = listener;
        }

        @Override
        public void close() {
            listener = null;
        }
    }
}
