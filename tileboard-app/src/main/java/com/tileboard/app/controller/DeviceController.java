package com.tileboard.app.controller;

import com.tileboard.app.dto.ApiResponse;
import com.tileboard.app.dto.ApiResponses;
import com.tileboard.app.service.device.DeviceConfiguration;
import com.tileboard.app.service.device.DeviceConfigurationService;
import com.tileboard.app.exception.DeviceNotConfiguredException;
import com.tileboard.app.dto.DeviceConfigurationRequest;
import com.tileboard.app.dto.DeviceConfigurationResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Manages the board's physical geometry (width x height in tiles).
 *
 * <p>Modeled as a single resource under PUT (idempotent "set the current
 * configuration to this") rather than POST/PUT-to-update, since there is
 * exactly one device configuration at any given time.
 */
@RestController
@RequestMapping("/api/v1/device")
public class DeviceController {

    private final DeviceConfigurationService deviceConfigurationService;

    public DeviceController(DeviceConfigurationService deviceConfigurationService) {
        this.deviceConfigurationService = deviceConfigurationService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<DeviceConfigurationResponse, Void>> getCurrentConfiguration() {
        return ApiResponses.ok("Device successfuly configured.", deviceConfigurationService.current()
                .map(DeviceConfigurationResponse::from)
                .orElseThrow(DeviceNotConfiguredException::new));
    }


    @PostMapping
    public ResponseEntity<ApiResponse<DeviceConfigurationResponse, Void>> configure(@RequestBody @Valid DeviceConfigurationRequest request) {
        DeviceConfiguration configuration =
                deviceConfigurationService.configure(request.width(), request.height());
        return ApiResponses.ok("Device successfuly configured.", DeviceConfigurationResponse.from(configuration));
    }
}
