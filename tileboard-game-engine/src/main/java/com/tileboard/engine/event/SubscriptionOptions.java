package com.tileboard.engine.event;

import java.util.Objects;

public record SubscriptionOptions(
        GameEventType filterType,
        String filterSessionId,
        int queueCapacity,
        EventOverflowPolicy policy) {

    public SubscriptionOptions {
        if (queueCapacity <= 0) {
            throw new IllegalArgumentException("queueCapacity must be > 0");
        }
        Objects.requireNonNull(policy, "policy");
    }

    public static SubscriptionOptions defaults(int queueCapacity) {
        return new SubscriptionOptions(null, null, queueCapacity, EventOverflowPolicy.DROP_OLDEST);
    }

    public SubscriptionOptions withPolicy(EventOverflowPolicy policy) {
        return new SubscriptionOptions(filterType, filterSessionId, queueCapacity, policy);
    }

    public SubscriptionOptions withType(GameEventType type) {
        return new SubscriptionOptions(type, filterSessionId, queueCapacity, policy);
    }

    public SubscriptionOptions withSession(String sessionId) {
        return new SubscriptionOptions(filterType, sessionId, queueCapacity, policy);
    }

    public SubscriptionOptions withQueueCapacity(int capacity) {
        return new SubscriptionOptions(filterType, filterSessionId, capacity, policy);
    }
}
