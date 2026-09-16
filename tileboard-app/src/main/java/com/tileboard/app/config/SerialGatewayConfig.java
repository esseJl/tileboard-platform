package com.tileboard.app.config;

import com.tileboard.serial.transport.SerialPortRegistry;
import com.tileboard.serial.transport.jserialcomm.JSerialCommPortRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the library's extension points to concrete, hardware-facing
 * implementations. This is intentionally the *only* place in the
 * application that knows a {@link JSerialCommPortRegistry} is being used -
 * swapping serial libraries later (or plugging in a mock for a
 * hardware-less demo mode) means changing this bean definition only.
 */
@Configuration
public class SerialGatewayConfig {

    @Bean
    public SerialPortRegistry serialPortRegistry() {
        return new JSerialCommPortRegistry();
    }
}
