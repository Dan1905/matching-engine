package com.trading.matching_engine.simulation;

import java.util.concurrent.atomic.AtomicLong;

import com.trading.matching_engine.engine.EngineCommand;
import com.trading.matching_engine.engine.OrderIngress;
import com.trading.matching_engine.engine.QueueBasedIngress;
import com.trading.matching_engine.matching.MatchingEngine;

/**
 * Measures matching-only throughput — NO Spring context, NO Redis, NO Postgres.
 * Just OrderIngress -> single worker thread -> MatchingEngine.processOrder().
 * Isolates the matching algorithm + queue hand-off from all I/O, so you can
 * directly compare against the full e2e pipeline number to confirm where the
 * bottleneck actually is.
 */
public class PureMatchingBenchmark {

    public static void main(String[] args) throws InterruptedException {
        int count = args.length > 0 ? Integer.parseInt(args[0]) : 100_000;

        OrderIngress ingress = new QueueBasedIngress();
        MatchingEngine engine = new MatchingEngine();
        AtomicLong processedCount = new AtomicLong();
        boolean[] running = { true };

        Thread worker = Thread.ofPlatform().name("pure-matching-worker").start(() -> {
            while (running[0]) {
                try {
                    EngineCommand cmd = ingress.take();
                    if (cmd instanceof EngineCommand.SubmitOrder submit) {
                        engine.processOrder(submit.order()); // result discarded — no persistence
                        processedCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        });

        OrderGenerator generator = new OrderGenerator(ingress);

        long start = System.nanoTime();
        for (int i = 0; i < count; i++) {
            generator.submitRandomOrder(i);
        }

        while (processedCount.get() < count) {
            Thread.sleep(1);
        }
        long elapsed = System.nanoTime() - start;

        running[0] = false;
        worker.interrupt();

        double seconds = elapsed / 1_000_000_000.0;
        double throughput = count / seconds;

        System.out.println("=== Pure Matching Throughput (no Redis, no Postgres) ===");
        System.out.println("Orders: " + count);
        System.out.printf("Wall clock: %.3f sec%n", seconds);
        System.out.printf("Throughput: %.0f orders/sec%n", throughput);
    }
}