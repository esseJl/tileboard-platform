package com.tileboard.serial.gateway;

import com.tileboard.serial.gateway.handshake.DeviceAddress;
import com.tileboard.serial.gateway.handshake.HandshakeCoordinator;
import com.tileboard.serial.gateway.handshake.SequentialIdSequenceValidator;
import com.tileboard.serial.protocol.Command;
import com.tileboard.serial.protocol.CommandType;
import com.tileboard.serial.protocol.Frame;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HandshakeCoordinatorTest {

    @Test
    void respondsToIdClearWithResolvedAddress() {
        List<Frame> sent = new ArrayList<>();
        HandshakeCoordinator coordinator = new HandshakeCoordinator(
                () -> DeviceAddress.forBoard(4, 2),
                new SequentialIdSequenceValidator(1),
                sent::add);

        coordinator.onFrame(Frame.of(Command.ID, CommandType.CLEAR));

        assertEquals(1, sent.size());
        Frame response = sent.get(0);
        assertEquals(Command.ID, response.command());
        assertEquals(CommandType.SET, response.commandType());
        assertArrayEquals(new byte[]{8, 4}, response.payload());
    }

    @Test
    void acceptsAValidSequentialAssignmentWithoutResponding() {
        List<Frame> sent = new ArrayList<>();
        HandshakeCoordinator coordinator = new HandshakeCoordinator(
                () -> DeviceAddress.forBoard(2, 2),
                new SequentialIdSequenceValidator(3),
                sent::add);

        coordinator.onFrame(Frame.of(Command.ID, CommandType.SET, new byte[]{1, 2, 3, 99}));

        assertTrue(sent.isEmpty());
    }

    @Test
    void asksToRestartOnInvalidAssignment() {
        List<Frame> sent = new ArrayList<>();
        HandshakeCoordinator coordinator = new HandshakeCoordinator(
                () -> DeviceAddress.forBoard(2, 2),
                new SequentialIdSequenceValidator(3),
                sent::add);

        coordinator.onFrame(Frame.of(Command.ID, CommandType.SET, new byte[]{1, 5, 3, 99}));

        assertEquals(1, sent.size());
        assertEquals(Frame.of(Command.ID, CommandType.CLEAR), sent.get(0));
    }

    @Test
    void ignoresFramesThatAreNotAboutId() {
        List<Frame> sent = new ArrayList<>();
        HandshakeCoordinator coordinator = new HandshakeCoordinator(
                () -> DeviceAddress.forBoard(2, 2),
                new SequentialIdSequenceValidator(1),
                sent::add);

        coordinator.onFrame(Frame.of(Command.DATA_IN, CommandType.SET, new byte[]{1, 2}));

        assertTrue(sent.isEmpty());
    }
}
