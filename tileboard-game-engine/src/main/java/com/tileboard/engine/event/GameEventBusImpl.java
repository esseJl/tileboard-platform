package com.tileboard.engine.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

public final class GameEventBusImpl implements GameEventBus, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(GameEventBusImpl.class);

    private final CopyOnWriteArrayList<Subscription> subscriptions = new CopyOnWriteArrayList<>();
    private final int defaultQueueCapacity;
    private final EventOverflowPolicy defaultPolicy;
    private final Duration blockTimeout;
    private final AtomicLong droppedEvents = new AtomicLong();

    public GameEventBusImpl() {
        this(256, EventOverflowPolicy.DROP_OLDEST);
    }

    public GameEventBusImpl(int defaultQueueCapacity, EventOverflowPolicy defaultPolicy) {
        this(defaultQueueCapacity, defaultPolicy, Duration.ofMillis(200));
    }

    public GameEventBusImpl(int defaultQueueCapacity, EventOverflowPolicy defaultPolicy, Duration blockTimeout) {
        this.defaultQueueCapacity = defaultQueueCapacity;
        this.defaultPolicy = Objects.requireNonNull(defaultPolicy, "defaultPolicy");
        this.blockTimeout = Objects.requireNonNull(blockTimeout, "blockTimeout");
    }

    @Override
    public void publish(GameEvent event) {
        Objects.requireNonNull(event, "event");
        for (Subscription sub : subscriptions) {
            if (sub.matches(event)) sub.offer(event);
        }
    }

    @Override
    public Runnable subscribe(GameEventListener listener) {
        return subscribe(listener, SubscriptionOptions.defaults(defaultQueueCapacity).withPolicy(defaultPolicy));
    }

    @Override
    public Runnable subscribe(GameEventType type, GameEventListener listener) {
        return subscribe(listener, SubscriptionOptions.defaults(defaultQueueCapacity)
                .withPolicy(defaultPolicy).withType(type));
    }

    @Override
    public Runnable subscribeSession(String sessionId, GameEventListener listener) {
        return subscribe(listener, SubscriptionOptions.defaults(defaultQueueCapacity)
                .withPolicy(defaultPolicy).withSession(sessionId));
    }

    @Override
    public Runnable subscribe(GameEventListener listener, SubscriptionOptions options) {
        Subscription sub = new Subscription(listener, options, blockTimeout.toNanos());
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

    @Override
    public void close() {
        subscriptions.forEach(Subscription::stop);
        subscriptions.clear();
    }

    private final class Subscription {
        private final GameEventListener listener;
        private final SubscriptionOptions options;
        private final long blockTimeoutNanos;
        private final BlockingQueue<GameEvent> blockingQueue;
        private final ArrayDeque<GameEvent> ring;
        private final ReentrantLock ringLock = new ReentrantLock();
        private final Semaphore ringAvailable = new Semaphore(0);
        private final ExecutorService worker;
        private volatile boolean running = true;

        Subscription(GameEventListener listener, SubscriptionOptions options, long blockTimeoutNanos) {
            this.listener = listener;
            this.options = options;
            this.blockTimeoutNanos = blockTimeoutNanos;
            if (options.policy() == EventOverflowPolicy.BLOCK) {
                this.blockingQueue = new LinkedBlockingQueue<>(options.queueCapacity());
                this.ring = null;
            } else {
                this.blockingQueue = null;
                this.ring = new ArrayDeque<>(options.queueCapacity());
            }
            this.worker = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "tileboard-eventbus-subscriber");
                t.setDaemon(true);
                return t;
            });
        }

        boolean matches(GameEvent event) {
            if (options.filterType() != null && options.filterType() != event.type()) return false;
            return options.filterSessionId() == null || options.filterSessionId().equals(event.sessionId());
        }

        void offer(GameEvent event) {
            if (options.policy() == EventOverflowPolicy.BLOCK) offerBlocking(event);
            else offerDropOldest(event);
        }

        private void offerBlocking(GameEvent event) {
            try {
                if (!blockingQueue.offer(event, blockTimeoutNanos, TimeUnit.NANOSECONDS)) {
                    log.warn("Subscriber did not drain within {}ns; dropping event {}", blockTimeoutNanos, event.type());
                    droppedEvents.incrementAndGet();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private void offerDropOldest(GameEvent event) {
            ringLock.lock();
            try {
                while (ring.size() >= options.queueCapacity()) {
                    if (ring.pollFirst() != null) droppedEvents.incrementAndGet();
                }
                ring.addLast(event);
            } finally {
                ringLock.unlock();
            }
            ringAvailable.release();
        }

        private GameEvent takeDropOldest(long timeoutMs) throws InterruptedException {
            if (!ringAvailable.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)) return null;
            ringLock.lock();
            try {
                return ring.pollFirst();
            } finally {
                ringLock.unlock();
            }
        }

        void start() {
            worker.submit(this::drainLoop);
        }

        void drainLoop() {
            while (running) {
                try {
                    GameEvent event = (options.policy() == EventOverflowPolicy.BLOCK)
                            ? blockingQueue.poll(1, TimeUnit.SECONDS)
                            : takeDropOldest(1000);
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