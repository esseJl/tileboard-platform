package com.tileboard.app.exception;

import org.springframework.http.HttpStatus;

/** Thrown when a game or command is requested but no serial connection to the board is open. */
public class GatewayNotConnectedException extends ApiException {

    public GatewayNotConnectedException() {
        super(HttpStatus.CONFLICT, "gateway_not_connected",
                "No active serial connection to the tile board. Connect the ports first.");
    }
}
