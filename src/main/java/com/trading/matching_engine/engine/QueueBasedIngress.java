package com.trading.matching_engine.engine;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import org.springframework.stereotype.Component;

@Component
public class QueueBasedIngress implements OrderIngress {
   private static final int CAPACITY = 100_000;
    private final BlockingQueue<EngineCommand> queue = new ArrayBlockingQueue<>(CAPACITY);

    @Override
    public boolean submit(EngineCommand command) {
        return queue.offer(command);
    }

    @Override
    public EngineCommand take() throws InterruptedException {
        return queue.take();
    }

    public boolean isEmpty() {
        return queue.isEmpty();
    }
}
