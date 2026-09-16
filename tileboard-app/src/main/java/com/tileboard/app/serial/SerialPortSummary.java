package com.tileboard.app.serial;

/** A serial port discovered on the host, independent of any vendor library's own type. */
public record SerialPortSummary(String systemName, String description) {
}
