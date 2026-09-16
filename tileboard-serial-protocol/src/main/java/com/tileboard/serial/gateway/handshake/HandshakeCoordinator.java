package com.tileboard.serial.gateway.handshake;

import com.tileboard.serial.gateway.FrameListener;
import com.tileboard.serial.protocol.Command;
import com.tileboard.serial.protocol.CommandType;
import com.tileboard.serial.protocol.Frame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Implements the tile-id addressing handshake with the controller:
 * <ul>
 *     <li>when the controller sends {@code ID}/{@code CLEAR} (it is asking to
 *     be told the board geometry), respond with {@code ID}/{@code SET}
 *     carrying the {@link DeviceAddress} from the configured {@link AddressResolver};</li>
 *     <li>when the controller reports back an id assignment (any other
 *     {@code ID} frame), validate it with the configured {@link SequenceValidator}
 *     and, if invalid, ask it to restart by sending {@code ID}/{@code CLEAR} again.</li>
 * </ul>
 *
 * <p>This class only depends on a {@code Consumer<Frame>} to send frames, not
 * on any concrete client, so it can be wired into any transport and unit
 * tested in isolation.
 */
public final class HandshakeCoordinator implements FrameListener {

    private static final Logger log = LoggerFactory.getLogger(HandshakeCoordinator.class);

    private final AddressResolver addressResolver;
    private final SequenceValidator sequenceValidator;
    private final Consumer<Frame> frameSender;

    public HandshakeCoordinator(AddressResolver addressResolver,
                                 SequenceValidator sequenceValidator,
                                 Consumer<Frame> frameSender) {
        this.addressResolver = Objects.requireNonNull(addressResolver, "addressResolver");
        this.sequenceValidator = Objects.requireNonNull(sequenceValidator, "sequenceValidator");
        this.frameSender = Objects.requireNonNull(frameSender, "frameSender");
    }

    @Override
    public void onFrame(Frame frame) {
        if (frame.command() != Command.ID) {
            return;
        }

        if (frame.commandType() == CommandType.CLEAR) {
            DeviceAddress address = addressResolver.resolveAddress();
            log.debug("Board requested ID/CLEAR - replying ID/SET with totalTiles={}, tilesPerRow={}",
                    address.totalTiles(), address.tilesPerRow());
            frameSender.accept(Frame.of(Command.ID, CommandType.SET, address.toPayload()));
            return;
        }

        boolean valid = sequenceValidator.isValid(frame.payload());
        log.debug("Board reported an ID sequence of {} byte(s): {}", frame.payloadLength(), valid ? "valid" : "INVALID, restarting handshake");
        if (!valid) {
            frameSender.accept(Frame.of(Command.ID, CommandType.CLEAR));
        }
    }
}
