package com.tileboard.engine.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public final class GameEventBusImpl implements GameEventBus, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(GameEventBusImpl.class);
    private final CopyOnWriteArrayList<Subscription> subscriptions = new CopyOnWriteArrayList<>();
    private final int defaultQueueCapacity;
    private final OverflowPolicy defaultPolicy;
    private final Duration blockTimeout;
    private final AtomicLong droppedEvents = new AtomicLong();

    public GameEventBusImpl() {
        this(256, OverflowPolicy.DROP_OLDEST);
    }

    public GameEventBusImpl(int defaultQueueCapacity, OverflowPolicy defaultPolicy) {
        this(defaultQueueCapacity, defaultPolicy, Duration.ofMillis(200));
    }

    public GameEventBusImpl(int defaultQueueCapacity, OverflowPolicy defaultPolicy, Duration blockTimeout) {
        this.defaultQueueCapacity = defaultQueueCapacity;
        this.defaultPolicy = defaultPolicy;
        this.blockTimeout = Objects.requireNonNull(blockTimeout, "blockTimeout");
    }

    @Override
    public void publish(GameEvent event) {
        Objects.requireNonNull(event);
        for (Subscription sub : subscriptions) {
            if (matches(sub, event)) sub.offer(event);
        }
    }

    @Override
    public Runnable subscribe(GameEventListener listener) {
        return subscribe(listener, null, null, defaultQueueCapacity, defaultPolicy);
    }

    @Override
    public Runnable subscribe(GameEventType type, GameEventListener listener) {
        return subscribe(listener, type, null, defaultQueueCapacity, defaultPolicy);
    }

    @Override
    public Runnable subscribeSession(String sessionId, GameEventListener listener) {
        return subscribe(listener, null, sessionId, defaultQueueCapacity, defaultPolicy);
    }

    public Runnable subscribe(GameEventListener listener, GameEventType filterType, String filterSessionId,
                              int queueCapacity, OverflowPolicy policy) {
        Subscription sub = new Subscription(listener, filterType, filterSessionId, queueCapacity, policy,
                blockTimeout.toNanos());
        subscriptions.add(sub);
        sub.start();
        return () -> {
            subscriptions.remove(sub);
            sub.stop();
        };
    }

    public long droppedEventCount() {
        return droppedEvents.get();
    }

    public int subscriberCount() {
        return subscriptions.size();
    }

    private boolean matches(Subscription sub, GameEvent event) {
        if (sub.filterType != null && sub.filterType != event.type()) return false;
        if (sub.filterSessionId != null && !sub.filterSessionId.equals(event.sessionId())) return false;
        return true;
    }

    @Override
    public void close() {
        subscriptions.forEach(Subscription::stop);
        subscriptions.clear();
    }

    public enum OverflowPolicy {BLOCK, DROP_OLDEST}

    private final class Subscription {
        final GameEventListener listener;
        final GameEventType filterType;
        final String filterSessionId;
        final OverflowPolicy policy;
        final long blockTimeoutNanos;
        final BlockingQueue<GameEvent> queue;
        final ExecutorService worker;
        volatile boolean running = true;

        Subscription(GameEventListener listener, GameEventType filterType, String filterSessionId,
                     int capacity, OverflowPolicy policy, long blockTimeoutNanos) {
            this.listener = listener;
            this.filterType = filterType;
            this.filterSessionId = filterSessionId;
            this.policy = policy;
            this.blockTimeoutNanos = blockTimeoutNanos;
            this.queue = new LinkedBlockingQueue<>(capacity);
            this.worker = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "tileboard-eventbus-subscriber");
                t.setDaemon(true);
                return t;
            });
        }

        void start() {
            worker.submit(this::drainLoop);
        }

        void offer(GameEvent event) {
            if (policy == OverflowPolicy.BLOCK) {
                try {
                    boolean accepted = queue.offer(event, blockTimeoutNanos, TimeUnit.NANOSECONDS);
                    if (!accepted) {
                        log.warn("Subscriber did not drain within {}ns; dropping event {} instead of blocking publisher",
                                blockTimeoutNanos, event.type());
                        droppedEvents.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            } else {
                while (!queue.offer(event)) {
                    GameEvent discarded = queue.poll();
                    if (discarded != null) droppedEvents.incrementAndGet();
                    else break;
                }
            }
        }

        void drainLoop() {
            while (running) {
                try {
                    GameEvent event = queue.poll(1, TimeUnit.SECONDS);
                    if (event == null) continue;
                    try {
                        listener.onEvent(event);
                    } catch (Throwable t) {
                        log.warn("Event listener threw while handling {}", event.type(), t);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        void stop() {
            running = false;
            worker.shutdownNow();
        }
    }
}