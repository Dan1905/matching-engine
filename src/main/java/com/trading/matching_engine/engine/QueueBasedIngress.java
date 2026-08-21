package com.trading.matching_engine.engine;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import org.springframework.stereotype.Component;

/**
 * The one place concurrency is handled: many producer threads, one consumer. The queue
 * has its own internal lock — what is removed is application-level lock contention on
 * the order book, not every lock in the process.
 */
@Component
public class QueueBasedIngress implements OrderIngress {
    private static final int CAPACITY = 100_000;
    private static final double SATURATION_THRESHOLD = 0.80;

    private final BlockingQueue<EngineCommand> queue = new ArrayBlockingQueue<>(CAPACITY);

    @Override
    public boolean submit(EngineCommand command) {
        return queue.offer(command);
    }

    @Override
    public EngineCommand take() throws InterruptedException {
        return queue.take();
    }

    @Override
    public boolean isEmpty() {
        return queue.isEmpty();
    }

    @Override
    public int size() {
        return queue.size();
    }

    @Override
    public boolean isSaturated() {
        return queue.size() >= CAPACITY * SATURATION_THRESHOLD;
    }
}
