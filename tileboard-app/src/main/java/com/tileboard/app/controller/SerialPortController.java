package com.tileboard.app.controller;

import com.tileboard.app.dto.*;
import com.tileboard.app.i18n.Messages;
import com.tileboard.app.service.serial.PortRole;
import com.tileboard.app.service.serial.SerialConnectionManager;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Discovering, assigning and (dis)connecting the serial link to the tile board.
 */
@RestController
@RequestMapping(path = "/api/v1/ports",produces = MediaType.APPLICATION_JSON_VALUE)
public class SerialPortController {

    private final SerialConnectionManager connectionManager;
    private final Messages messages;

    public SerialPortController(SerialConnectionManager connectionManager, Messages messages) {
        this.connectionManager = connectionManager;
        this.messages = messages;
    }

    @GetMapping
    public ResponseEntity<ApiResponse> listAvailablePorts() {
        List<SerialPortResponse> list = connectionManager.listAvailablePorts().stream()
                .map(SerialPortResponse::from)
                .toList();
        return ApiResponses.ok(messages.get("serial.all.available",list.size()), list);
    }

    @PostMapping("/{role}/assign")
    public ResponseEntity<ApiResponse> assignPort(@PathVariable PortRole role, @RequestBody @Valid AssignPortRequest request) {
        connectionManager.assign(role, request.portName());
        return ApiResponses.ok();
    }

    @GetMapping(value = "/status")
    public ResponseEntity<ApiResponse> status() {
        return ApiResponses.ok(ConnectionStatusResponse.of(connectionManager.connectionState(), connectionManager.currentAssignment()));
    }

    @PostMapping("/connect")
    public ResponseEntity<ApiResponse> connect() {
        connectionManager.connect();
        return status();
    }

    @PostMapping("/disconnect")
    public ResponseEntity<ApiResponse> disconnect() {
        connectionManager.disconnect();
        return status();
    }
}
