package com.tileboard.app.device;

import com.tileboard.app.common.exception.DeviceNotConfiguredException;
import com.tileboard.app.device.dto.DeviceConfigurationRequest;
import com.tileboard.app.device.dto.DeviceConfigurationResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
    public DeviceConfigurationResponse getCurrentConfiguration() {
        return deviceConfigurationService.current()
                .map(DeviceConfigurationResponse::from)
                .orElseThrow(DeviceNotConfiguredException::new);
    }


    @PutMapping
    public DeviceConfigurationResponse configure(@RequestBody @Valid DeviceConfigurationRequest request) {
        DeviceConfiguration configuration =
                deviceConfigurationService.configure(request.width(), request.height());
        return DeviceConfigurationResponse.from(configuration);
    }
}
