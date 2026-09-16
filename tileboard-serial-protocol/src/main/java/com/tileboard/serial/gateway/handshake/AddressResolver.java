package com.tileboard.serial.gateway.handshake;

/**
 * Supplies the {@link DeviceAddress} to answer with when the tile controller
 * requests one (an {@code ID}/{@code CLEAR} frame). Typically a simple
 * {@code () -> DeviceAddress.forBoard(width, height)} lambda over whatever
 * board geometry the application is using.
 */
@FunctionalInterface
public interface AddressResolver {

    DeviceAddress resolveAddress();
}
