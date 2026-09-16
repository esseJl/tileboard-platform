package com.tileboard.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class TileboardApplication {

    public static void main(String[] args) {
        SpringApplication.run(TileboardApplication.class, args);
    }
}
