package com.trading.matching_engine.simulation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.Random;
import java.util.UUID;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import com.trading.matching_engine.MatchingEngineApplication;
import com.trading.matching_engine.domain.Order;
import com.trading.matching_engine.domain.OrderStatus;
import com.trading.matching_engine.domain.OrderType;
import com.trading.matching_engine.domain.Side;
import com.trading.matching_engine.engine.EngineCommand;
import com.trading.matching_engine.engine.MatchingEngineWorker;
import com.trading.matching_engine.engine.QueueBasedIngress;
import com.trading.matching_engine.engine.OrderIngress;
import com.trading.matching_engine.persistence.AsyncPersistenceWriter;


public class OrderGenerator {
    private final OrderIngress ingress;
    private final Random random = new Random();
    private static final BigDecimal MID = new BigDecimal("150.00");
    private static final String[] SYMBOLS = {"AAPL", "MSFT", "GOOGL"};

    public OrderGenerator(OrderIngress ingress) {
        this.ingress = ingress;
    }

    public static void main(String[] args) throws InterruptedException {
        int count = args.length > 0 ? Integer.parseInt(args[0]) : 1000;
        boolean concurrent = args.length > 1 && "concurrent".equalsIgnoreCase(args[1]);
        int producerThreads = args.length > 2 ? Integer.parseInt(args[2]) : 8;

        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(MatchingEngineApplication.class)
            .web(WebApplicationType.NONE)
            .run(args)) {
            QueueBasedIngress ingress = context.getBean(QueueBasedIngress.class);
            MatchingEngineWorker worker = context.getBean(MatchingEngineWorker.class);
            AsyncPersistenceWriter writer = context.getBean(AsyncPersistenceWriter.class);
            OrderGenerator generator = new OrderGenerator(ingress);

            if (concurrent) {
                generator.generateConcurrent(producerThreads, count);
            } else {
                generator.generateAndMeasureSubmitLatency(count);
            }

            waitForDrain(worker, 30_000);
        }
    }

    /**
     * Submits `count` orders as fast as possible from THIS thread, measuring how long
     * ingress.submit() takes — this is producer-side latency (time to hand off to the queue),
     * NOT matching latency. Matching latency happens asynchronously on the worker thread and
     * must be measured separately (see measureEndToEndLatency below, or just check
     * AsyncPersistenceWriter timestamps / Redis status timestamps in a real run).
     */

    public void generateAndMeasureSubmitLatency(int count) {
        long[] latencies = new long[count];

        for (int i = 0; i < count; i++) {
            Order order = randomOrder(i);

            long start = System.nanoTime();
            boolean accepted = ingress.submit(new EngineCommand.SubmitOrder(order));
            long end = System.nanoTime();

            latencies[i] = (end - start);

            if (!accepted) {
                System.out.println("WARNING: queue full, order " + i + " rejected");
            }
        }

        printStats(latencies, count, "Submit (producer-side) latency");
    }

    /**
     * Multi-threaded load test — simulates N concurrent producer threads all submitting
     * orders simultaneously. This is the scenario that actually stresses the ingress queue
     * and is what your benchmark numbers should be based on, not a single-threaded loop.
     */
    public void generateConcurrent(int producerThreads, int ordersPerThread) throws InterruptedException {
        Thread[] threads = new Thread[producerThreads];
        long overallStart = System.nanoTime();

        for (int t = 0; t < producerThreads; t++) {
            final int threadId = t;
            threads[t] = Thread.ofPlatform().start(() -> {
                for (int i = 0; i < ordersPerThread; i++) {
                    Order order = randomOrder(threadId * ordersPerThread + i);
                    ingress.submit(new EngineCommand.SubmitOrder(order));
                }
            });
        }

        for (Thread t : threads) t.join();
        long overallEnd = System.nanoTime();

        int totalOrders = producerThreads * ordersPerThread;
        double totalSeconds = (overallEnd - overallStart) / 1_000_000_000.0;
        double throughput = totalOrders / totalSeconds;

        System.out.println("=== Concurrent Submit Load Test ===");
        System.out.println("Producer threads: " + producerThreads);
        System.out.println("Orders per thread: " + ordersPerThread);
        System.out.println("Total orders submitted: " + totalOrders);
        System.out.printf("Wall clock time: %.3f sec%n", totalSeconds);
        System.out.printf("Submit throughput: %.0f orders/sec%n", throughput);
    }

    private Order randomOrder(int i) {
        Side side = random.nextBoolean() ? Side.BUY : Side.SELL;
        boolean isMarket = random.nextInt(10) == 0; // 10% market orders
        BigDecimal offset = BigDecimal.valueOf(random.nextInt(5));
        BigDecimal price = side == Side.BUY ? MID.subtract(offset) : MID.add(offset);
        long qty = 100 + random.nextInt(900);

        return Order.builder()
            .id(UUID.randomUUID().toString())
            .symbol(SYMBOLS[random.nextInt(SYMBOLS.length)])
            .side(side)
            .orderType(isMarket ? OrderType.MARKET : OrderType.LIMIT)
            .price(isMarket ? null : price)
            .originalQuantity(qty)
            .remainingQuantity(qty)
            .status(OrderStatus.NEW)
            .clientOrderId("SIM-" + i)
            .createdAt(Instant.now())
            .build();
    }

    private void printStats(long[] latencies, int count, String label) {
        long[] sorted = latencies.clone();
        Arrays.sort(sorted);

        long p50 = sorted[(int) (count * 0.50)];
        long p95 = sorted[(int) (count * 0.95)];
        long p99 = sorted[(int) (count * 0.99)];

        double totalSeconds = Arrays.stream(latencies).sum() / 1_000_000_000.0;
        double throughput = count / totalSeconds;

        System.out.println("=== " + label + " ===");
        System.out.println("Orders: " + count);
        System.out.printf("p50: %.3f ms%n", p50 / 1_000_000.0);
        System.out.printf("p95: %.3f ms%n", p95 / 1_000_000.0);
        System.out.printf("p99: %.3f ms%n", p99 / 1_000_000.0);
        System.out.printf("Throughput: %.0f orders/sec%n", throughput);
    }

    private static void waitForDrain(MatchingEngineWorker worker, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (worker.isIdle()) {
                return;
            }
            Thread.sleep(50);
        }

        System.out.println("WARNING: timed out waiting for engine drain; shutting down anyway");
    }
}
