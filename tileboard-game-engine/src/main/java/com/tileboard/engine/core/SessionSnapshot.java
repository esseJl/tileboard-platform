package com.tileboard.engine.core;

import java.util.Map;

public record SessionSnapshot(Map<String, Integer> scores, int level, String status, long elapsedSeconds) {
}
