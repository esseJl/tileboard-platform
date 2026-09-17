package com.tileboard.engine.spring;

import com.tileboard.serial.gateway.TileGatewayClient;
import com.tileboard.serial.gateway.handshake.DeviceAddress;
import com.tileboard.serial.transport.SerialPortConfig;
import com.tileboard.serial.transport.SerialTransport;
import com.tileboard.serial.transport.jserialcomm.JSerialCommPortRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Factory that creates and starts a {@link TileGatewayClient} from the
 * engine properties. Separated from the auto-configuration class so it can
 * be unit-tested without Spring context.
 */
public final class TileboardGatewayAdapter {

    private static final Logger log = LoggerFactory.getLogger(TileboardGatewayAdapter.class);

    private TileboardGatewayAdapter() {}

    public static TileGatewayClient create(TileboardEngineProperties props) {
        log.info("Opening serial port '{}' for Tileboard engine", props.getSerialPort());

        SerialTransport port = new JSerialCommPortRegistry()
                .open(props.getSerialPort(), SerialPortConfig.defaults());

        TileGatewayClient.Builder builder = TileGatewayClient.builder().transport(port);

        TileGatewayClient client = builder.build();

        if (props.isHandshakeEnabled()) {
            client.enableIdHandshake(
                    () -> DeviceAddress.forBoard(props.getBoardWidth(), props.getBoardHeight()),
                    props.getHandshakeMinimumSequence()
            );
        }

        client.start();
        log.info("TileGatewayClient started on '{}'", props.getSerialPort());
        return client;
    }
}