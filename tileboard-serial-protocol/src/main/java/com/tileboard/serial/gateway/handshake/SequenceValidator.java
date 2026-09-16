package com.tileboard.serial.gateway.handshake;

/**
 * Validates the payload of an incoming {@code ID}/{@code SET} frame, in
 * which the controller reports back the tile ids it assigned. Pluggable so
 * applications can enforce whatever acceptance rule their firmware version
 * expects; {@link SequentialIdSequenceValidator} provides the default rule.
 */
@FunctionalInterface
public interface SequenceValidator {

    boolean isValid(byte[] payload);
}
