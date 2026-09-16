package com.tileboard.app.controller;

import com.tileboard.app.dto.AssignPortRequest;
import com.tileboard.app.dto.ConnectionStatusResponse;
import com.tileboard.app.dto.SerialPortResponse;
import com.tileboard.app.service.serial.PortRole;
import com.tileboard.app.service.serial.SerialConnectionManager;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Discovering, assigning and (dis)connecting the serial link to the tile board. */
@RestController
@RequestMapping("/api/v1/ports")
public class SerialPortController {

    private final SerialConnectionManager connectionManager;

    public SerialPortController(SerialConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
    }

    @GetMapping
    public List<SerialPortResponse> listAvailablePorts() {
        return connectionManager.listAvailablePorts().stream()
                .map(SerialPortResponse::from)
                .toList();
    }

    @PostMapping("/{role}/assign")
    public ResponseEntity<Void> assignPort(@PathVariable PortRole role, @RequestBody @Valid AssignPortRequest request) {
        connectionManager.assign(role, request.portName());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/status")
    public ConnectionStatusResponse status() {
        return ConnectionStatusResponse.of(
                connectionManager.connectionState(),
                connectionManager.currentAssignment());
    }

    @PostMapping("/connect")
    public ConnectionStatusResponse connect() {
        connectionManager.connect();
        return status();
    }

    @PostMapping("/disconnect")
    public ConnectionStatusResponse disconnect() {
        connectionManager.disconnect();
        return status();
    }
}
