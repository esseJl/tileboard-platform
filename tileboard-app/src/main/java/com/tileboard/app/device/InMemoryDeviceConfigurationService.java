package com.tileboard.app.device;

import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * In-memory {@link DeviceConfigurationService}.
 *
 * <p>TODO(persistence): once a persistence layer is introduced, replace this
 * with an implementation backed by a repository. Every consumer of
 * {@link DeviceConfigurationService} was written against the interface only,
 * so that change should not ripple beyond this class and its wiring.
 */
@Service
public class InMemoryDeviceConfigurationService implements DeviceConfigurationService {

    private final AtomicReference<DeviceConfiguration> configuration = new AtomicReference<>();

    @Override
    public Optional<DeviceConfiguration> current() {
        return Optional.ofNullable(configuration.get());
    }

    @Override
    public DeviceConfiguration configure(int width, int height) {
        DeviceConfiguration updated = new DeviceConfiguration(width, height);
        configuration.set(updated);
        return updated;
    }
}
