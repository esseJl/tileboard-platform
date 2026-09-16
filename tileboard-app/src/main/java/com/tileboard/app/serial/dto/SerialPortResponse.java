package com.tileboard.app.serial.dto;

import com.tileboard.app.serial.SerialPortSummary;

public record SerialPortResponse(String systemName, String description) {

    public static SerialPortResponse from(SerialPortSummary summary) {
        return new SerialPortResponse(summary.systemName(), summary.description());
    }
}
