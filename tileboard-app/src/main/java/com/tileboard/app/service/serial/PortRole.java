package com.tileboard.app.service.serial;

/**
 * The two directions of a serial link with the tile controller. Kept as a
 * two-value enum (rather than a boolean) purely for readability at call
 * sites - {@code assign(PortRole.OUT, "COM3")} reads far better than
 * {@code assign(false, "COM3")}.
 */
public enum PortRole {
    IN,
    OUT
}
