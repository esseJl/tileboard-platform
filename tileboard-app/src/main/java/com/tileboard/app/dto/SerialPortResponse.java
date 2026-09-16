package com.tileboard.app.dto;

import com.tileboard.app.service.serial.SerialPortSummary;

public record SerialPortResponse(String systemName, String description) {

    public static SerialPortResponse from(SerialPortSummary summary) {
        return new SerialPortResponse(summary.systemName(), summary.description());
    }
}
